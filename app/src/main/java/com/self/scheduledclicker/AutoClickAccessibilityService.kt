package com.self.scheduledclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 負責：
 * 1. 常駐前景保活（防止重新讀取或滑掉 App 時無障礙服務被系統切斷）
 * 2. 顯示懸浮控制列（即時時鐘 HH:mm:ss.S、新增/減少點位、定時待命、立即測試、緊急停止）
 * 3. 顯示可拖曳的編號準星（#1、#2、#3...），確保每次更新或減少點位時徹底移除舊準星不殘留
 * 4. 時間到達指定時刻（精準至 0.1 秒）時，依序針對每個點位執行指定次數的自動點擊與循環
 */
class AutoClickAccessibilityService : AccessibilityService(), Choreographer.FrameCallback {

    enum class EngineState {
        IDLE,       // 自由拖曳編輯準星中
        ARMED,      // 定時待命中（準星已鎖定穿透，等待目標時刻到達）
        CLICKING    // 正在依序執行多點連點
    }

    companion object {
        const val ACTION_STATE_CHANGED = "com.self.scheduledclicker.STATE_CHANGED"
        private const val CHANNEL_ID = "scheduled_multi_clicker_channel"
        private const val NOTIF_ID = 2001

        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        @Volatile
        var isOverlayShowing: Boolean = false
            private set

        @Volatile
        var currentState: EngineState = EngineState.IDLE
            private set
    }

    private inner class TargetMarkerHolder(
        var index: Int,
        val config: TargetPointConfig,
        val view: TargetCrosshairView,
        val params: WindowManager.LayoutParams
    )

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var controlPanelView: LinearLayout? = null
    private lateinit var controlPanelParams: WindowManager.LayoutParams
    private var tvClockAndStatus: TextView? = null
    private var btnArmOrStop: Button? = null
    private var btnTestRun: Button? = null
    private var btnAddPt: Button? = null
    private var btnRemPt: Button? = null

    private val markerHolders = mutableListOf<TargetMarkerHolder>()

    private var scheduledTriggerEpochMs: Long = 0L
    private var clickSequenceToken: Int = 0
    private var cachedTzOffset: Int = TimeZone.getDefault().rawOffset
    private var lastTzCheckMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ClickerPrefs.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
        startForegroundKeepAlive()

        if (!ClickerPrefs.isNtpSynced) {
            thread(start = true) {
                SntpClient.syncTimeBestOf().onSuccess { ntp ->
                    ClickerPrefs.updateNtpSync(ntp.ntpTimeMs, ntp.elapsedRealtimeMs, ntp.roundTripTimeMs)
                }
            }
        }
        broadcastState()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 當使用者從最近使用的應用程式滑掉主畫面時，保持無障礙服務常駐不斷線
        super.onTaskRemoved(rootIntent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不讀取任何螢幕內容，僅使用手勢點擊功能
    }

    override fun onInterrupt() {
        stopAllExecution()
    }

    override fun onDestroy() {
        stopAllExecution()
        hideOverlay()
        instance = null
        broadcastState()
        super.onDestroy()
    }

    private fun startForegroundKeepAlive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "定時多點連點器常駐服務",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "確保重新開啟程式或切換背景時，無障礙自動點擊服務不會被系統中斷"
                    setShowBadge(false)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(this, MainActivity::class.java)
            val pendingOpen = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_clicker)
                .setContentTitle("定時多點連點器已就緒")
                .setContentText("無障礙點擊服務保持連線中 · 點擊返回設定面板")
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 顯示或更新懸浮控制面板與所有編號準星 ①②③...
     */
    fun showOrUpdateOverlay() {
        mainHandler.post {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先授予「懸浮視窗」權限", Toast.LENGTH_SHORT).show()
                return@post
            }
            if (!isOverlayShowing) {
                isOverlayShowing = true
                createControlPanel()
                rebuildTargetMarkers()
                Choreographer.getInstance().removeFrameCallback(this)
                Choreographer.getInstance().postFrameCallback(this)
                broadcastState()
            } else {
                rebuildTargetMarkers()
                updateControlPanelUi()
                broadcastState()
            }
        }
    }

    /**
     * 隱藏懸浮面板與所有準星（徹底清除螢幕上所有視窗）
     */
    fun hideOverlay() {
        mainHandler.post {
            stopAllExecution()
            isOverlayShowing = false
            Choreographer.getInstance().removeFrameCallback(this)
            clearTargetMarkers()
            removeControlPanelViewSafely()
            broadcastState()
        }
    }

    private fun removeControlPanelViewSafely() {
        val v = controlPanelView ?: return
        controlPanelView = null
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Exception) {
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 啟動「定時待命」模式：鎖定所有準星（啟用觸控穿透），等設定時刻一到立即開點
     */
    fun startScheduledStandby() {
        if (!isOverlayShowing) {
            showOrUpdateOverlay()
        }
        if (ClickerPrefs.points.isEmpty()) {
            Toast.makeText(this, "請至少設定 1 個點位", Toast.LENGTH_SHORT).show()
            return
        }
        val now = ClickerPrefs.getAccurateTimeMillis()
        scheduledTriggerEpochMs = ClickerPrefs.computeNextTriggerEpochMs(now)
        currentState = EngineState.ARMED
        setMarkersTouchPassthrough(true)
        updateControlPanelUi()
        broadcastState()

        val waitSec = ((scheduledTriggerEpochMs - now) / 1000.0).coerceAtLeast(0.0)
        Toast.makeText(
            this,
            String.format("⏰ 已進入定時待命！將於 %s (約 %.1f 秒後) 自動依序點擊", ClickerPrefs.formatTargetTimeString(), waitSec),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 立即測試執行一次完整的多點依序連點
     */
    fun startImmediateTest() {
        if (!isOverlayShowing) {
            showOrUpdateOverlay()
        }
        if (currentState == EngineState.CLICKING) {
            stopAllExecution()
            return
        }
        currentState = EngineState.CLICKING
        setMarkersTouchPassthrough(true)
        updateControlPanelUi()
        broadcastState()

        val token = ++clickSequenceToken
        mainHandler.postDelayed({
            if (token == clickSequenceToken && currentState == EngineState.CLICKING) {
                executeMultiPointSequence(token)
            }
        }, 60L)
    }

    /**
     * 緊急停止待命或點擊，恢復準星可拖曳狀態
     */
    fun stopAllExecution(showToast: Boolean = false) {
        clickSequenceToken++
        val wasRunning = currentState != EngineState.IDLE
        currentState = EngineState.IDLE
        setMarkersTouchPassthrough(false)
        updateControlPanelUi()
        broadcastState()
        if (showToast && wasRunning) {
            Toast.makeText(this, "⏹️ 已停止並解鎖準星位置", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createControlPanel() {
        removeControlPanelViewSafely()
        val context = this
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8f)
            setPadding(pad, dp(6f), pad, dp(6f))
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 15, 23, 42))
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(1.2f), Color.parseColor("#38BDF8"))
            }
        }

        // 第一列：可拖曳標題 + 即時時間與狀態
        val tvHeader = TextView(context).apply {
            text = "✥ 00:00:00.0 | 編輯準星中"
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        }
        tvClockAndStatus = tvHeader
        root.addView(tvHeader)

        // 第二列：操作按鈕列
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnAddPt = createMiniButton("➕點", "#334155", Color.WHITE) {
            if (currentState != EngineState.IDLE) {
                Toast.makeText(context, "請先停止待命再新增點位", Toast.LENGTH_SHORT).show()
                return@createMiniButton
            }
            ClickerPrefs.addNewPoint(context)
            rebuildTargetMarkers()
            broadcastState()
        }

        btnRemPt = createMiniButton("➖點", "#334155", Color.WHITE) {
            if (currentState != EngineState.IDLE) {
                Toast.makeText(context, "請先停止待命再減少點位", Toast.LENGTH_SHORT).show()
                return@createMiniButton
            }
            if (ClickerPrefs.removeLastPoint(context)) {
                rebuildTargetMarkers()
                broadcastState()
            } else {
                Toast.makeText(context, "至少需保留 1 個點位", Toast.LENGTH_SHORT).show()
            }
        }

        btnArmOrStop = createMiniButton("⏰待命", "#00E676", Color.parseColor("#0F172A")) {
            if (currentState == EngineState.IDLE) {
                startScheduledStandby()
            } else {
                stopAllExecution(showToast = true)
            }
        }

        btnTestRun = createMiniButton("▶️測試", "#38BDF8", Color.parseColor("#0F172A")) {
            if (currentState == EngineState.CLICKING) {
                stopAllExecution(showToast = true)
            } else {
                startImmediateTest()
            }
        }

        val btnOpenApp = createMiniButton("⚙️設定", "#475569", Color.WHITE) {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        val btnClose = createMiniButton("✖", "#EF4444", Color.WHITE) {
            hideOverlay()
        }

        btnRow.addView(btnAddPt)
        btnRow.addView(btnRemPt)
        btnRow.addView(btnArmOrStop)
        btnRow.addView(btnTestRun)
        btnRow.addView(btnOpenApp)
        btnRow.addView(btnClose)
        root.addView(btnRow)

        controlPanelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ClickerPrefs.getPanelX(context)
            y = ClickerPrefs.getPanelY(context)
        }

        // 拖曳控制面板手勢
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        val dragListener = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = controlPanelParams.x
                    initY = controlPanelParams.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    controlPanelParams.x = initX + (ev.rawX - touchX).toInt()
                    controlPanelParams.y = initY + (ev.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(root, controlPanelParams)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ClickerPrefs.savePanelPosition(context, controlPanelParams.x, controlPanelParams.y)
                    true
                }
                else -> false
            }
        }
        tvHeader.setOnTouchListener(dragListener)
        root.setOnTouchListener(dragListener)

        controlPanelView = root
        windowManager.addView(root, controlPanelParams)
        updateControlPanelUi()
    }

    private fun createMiniButton(
        title: String,
        bgHex: String,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val padH = dp(7f)
            val padV = dp(4f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(6f).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4f)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    /**
     * 根據 ClickerPrefs.points 重新建立螢幕上的編號準星 #1, #2, #3...
     * 注意：若目前懸浮窗未開啟 (isOverlayShowing == false)，只會清除殘留準星，不會新增視窗！
     */
    @SuppressLint("ClickableViewAccessibility")
    fun rebuildTargetMarkers() {
        mainHandler.post {
            clearTargetMarkers()
            if (!isOverlayShowing) return@post

            val sizePx = dp(46f)
            val passthrough = currentState != EngineState.IDLE

            for ((idx, ptConfig) in ClickerPrefs.points.withIndex()) {
                val number = idx + 1
                val markerView = TargetCrosshairView(this, number, ptConfig.clickCount).apply {
                    alpha = if (passthrough) 0.55f else 0.95f
                }

                val flags = buildMarkerWindowFlags(passthrough)
                val params = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = ptConfig.x
                    y = ptConfig.y
                }

                var startX = 0
                var startY = 0
                var rawStartX = 0f
                var rawStartY = 0f
                var moved = false

                markerView.setOnTouchListener { _, ev ->
                    if (currentState != EngineState.IDLE) return@setOnTouchListener false
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = params.x
                            startY = params.y
                            rawStartX = ev.rawX
                            rawStartY = ev.rawY
                            moved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (ev.rawX - rawStartX).toInt()
                            val dy = (ev.rawY - rawStartY).toInt()
                            if (abs(dx) > 5 || abs(dy) > 5) moved = true
                            params.x = startX + dx
                            params.y = startY + dy
                            ptConfig.x = params.x
                            ptConfig.y = params.y
                            try {
                                windowManager.updateViewLayout(markerView, params)
                            } catch (_: Exception) {
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (moved) {
                                ClickerPrefs.savePoints(this)
                            } else {
                                // 單擊準星可快速循環切換該點位的點擊次數 (1 -> 2 -> 3 -> 5 -> 10 -> 1)
                                val nextCount = when (ptConfig.clickCount) {
                                    1 -> 2
                                    2 -> 3
                                    3 -> 5
                                    5 -> 10
                                    else -> 1
                                }
                                ptConfig.clickCount = nextCount
                                markerView.clickCount = nextCount
                                markerView.invalidate()
                                ClickerPrefs.savePoints(this)
                                broadcastState()
                                Toast.makeText(
                                    this,
                                    "點位 #$number 設定為連點 $nextCount 次",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager.addView(markerView, params)
                markerHolders.add(TargetMarkerHolder(number, ptConfig, markerView, params))
            }
        }
    }

    /**
     * 強制立即從 WindowManager 移除所有準星視圖（不檢查 isAttachedToWindow，杜絕同幀重複呼叫造成的孤兒殘留視窗）
     */
    private fun clearTargetMarkers() {
        val copy = markerHolders.toList()
        markerHolders.clear()
        for (h in copy) {
            try {
                windowManager.removeViewImmediate(h.view)
            } catch (_: Exception) {
                try {
                    windowManager.removeView(h.view)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun buildMarkerWindowFlags(passthrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (passthrough) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun setMarkersTouchPassthrough(passthrough: Boolean) {
        val flags = buildMarkerWindowFlags(passthrough)
        for (h in markerHolders) {
            h.view.alpha = if (passthrough) 0.50f else 0.95f
            if (h.params.flags != flags) {
                h.params.flags = flags
                try {
                    windowManager.updateViewLayout(h.view, h.params)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun updateControlPanelUi() {
        val armBtn = btnArmOrStop ?: return
        val testBtn = btnTestRun ?: return
        when (currentState) {
            EngineState.IDLE -> {
                armBtn.text = "⏰待命"
                armBtn.setTextColor(Color.parseColor("#0F172A"))
                armBtn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00E676"))
                    cornerRadius = dp(6f).toFloat()
                }
                testBtn.text = "▶️測試"
            }
            EngineState.ARMED -> {
                armBtn.text = "⏹️停止"
                armBtn.setTextColor(Color.WHITE)
                armBtn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EF4444"))
                    cornerRadius = dp(6f).toFloat()
                }
                testBtn.text = "▶️立即開點"
            }
            EngineState.CLICKING -> {
                armBtn.text = "⏹️急停"
                armBtn.setTextColor(Color.WHITE)
                armBtn.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EF4444"))
                    cornerRadius = dp(6f).toFloat()
                }
                testBtn.text = "⏹️停止"
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isOverlayShowing) return
        val now = ClickerPrefs.getAccurateTimeMillis()

        // 檢查是否到達定時觸發時間
        if (currentState == EngineState.ARMED && now >= scheduledTriggerEpochMs) {
            currentState = EngineState.CLICKING
            updateControlPanelUi()
            broadcastState()
            val token = ++clickSequenceToken
            executeMultiPointSequence(token)
        }

        updateHeaderClockAndState(now)
        Choreographer.getInstance().postFrameCallback(this)
    }

    @SuppressLint("SetTextI18n")
    private fun updateHeaderClockAndState(nowMs: Long) {
        val tv = tvClockAndStatus ?: return
        if (nowMs - lastTzCheckMs > 60_000L) {
            cachedTzOffset = TimeZone.getDefault().getOffset(nowMs)
            lastTzCheckMs = nowMs
        }
        val localMs = nowMs + cachedTzOffset
        val totalSec = localMs / 1000L
        val deci = (((localMs % 1000L) + 1000L).toInt() % 1000) / 100
        val sec = (((totalSec % 60L) + 60L) % 60L).toInt()
        val min = ((((totalSec / 60L) % 60L) + 60L) % 60L).toInt()
        val hr = ((((totalSec / 3600L) % 24L) + 24L) % 24L).toInt()

        val timeStr = String.format("%02d:%02d:%02d.%d", hr, min, sec, deci)
        val statusStr = when (currentState) {
            EngineState.IDLE -> "可拖曳準星 (共${ClickerPrefs.points.size}點·可循環${ClickerPrefs.totalCycles}輪)"
            EngineState.ARMED -> {
                val remainSec = ((scheduledTriggerEpochMs - nowMs) / 1000.0).coerceAtLeast(0.0)
                String.format("⏳待命 %s (剩%.1fs)", ClickerPrefs.formatTargetTimeString(), remainSec)
            }
            EngineState.CLICKING -> "⚡ 正在依序自動點擊中..."
        }
        tv.text = "✥ $timeStr | $statusStr"
    }

    /**
     * 依序執行多點連點：
     * 外迴圈：第 1 輪 .. totalCycles 輪
     *   內迴圈：點位 ① .. 點位 Ⓝ
     *     點位連點：第 1 次 .. pt.clickCount 次
     */
    private fun executeMultiPointSequence(token: Int) {
        if (token != clickSequenceToken || currentState != EngineState.CLICKING) return

        data class ExecutionTarget(
            val index: Int,
            val centerX: Float,
            val centerY: Float,
            val clickCount: Int,
            val delayAfterMs: Long
        )

        val targets = mutableListOf<ExecutionTarget>()
        val loc = IntArray(2)
        for (h in markerHolders) {
            h.view.getLocationOnScreen(loc)
            val cx = loc[0] + h.view.width / 2f
            val cy = loc[1] + h.view.height / 2f
            targets.add(
                ExecutionTarget(
                    index = h.index,
                    centerX = cx,
                    centerY = cy,
                    clickCount = h.config.clickCount.coerceAtLeast(1),
                    delayAfterMs = h.config.delayAfterMs.coerceAtLeast(20L)
                )
            )
        }

        if (targets.isEmpty()) {
            stopAllExecution()
            return
        }

        val totalCycles = ClickerPrefs.totalCycles.coerceAtLeast(1)
        val samePointInterval = ClickerPrefs.samePointIntervalMs.coerceAtLeast(20L)

        fun runStep(cycleIdx: Int, targetIdx: Int, clickIdx: Int) {
            if (token != clickSequenceToken || currentState != EngineState.CLICKING) return

            if (cycleIdx >= totalCycles) {
                stopAllExecution()
                Toast.makeText(
                    this,
                    "✅ 多點依序連點完成！（共執行 $totalCycles 輪）",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val target = targets[targetIdx]
            performTapAt(target.centerX, target.centerY, durationMs = 22L) {
                if (token != clickSequenceToken || currentState != EngineState.CLICKING) return@performTapAt

                val nextClickIdx = clickIdx + 1
                if (nextClickIdx < target.clickCount) {
                    mainHandler.postDelayed({
                        runStep(cycleIdx, targetIdx, nextClickIdx)
                    }, samePointInterval)
                } else {
                    val nextTargetIdx = targetIdx + 1
                    if (nextTargetIdx < targets.size) {
                        mainHandler.postDelayed({
                            runStep(cycleIdx, nextTargetIdx, 0)
                        }, target.delayAfterMs)
                    } else {
                        mainHandler.postDelayed({
                            runStep(cycleIdx + 1, 0, 0)
                        }, target.delayAfterMs)
                    }
                }
            }
        }

        runStep(0, 0, 0)
    }

    private fun performTapAt(screenX: Float, screenY: Float, durationMs: Long = 22L, onComplete: () -> Unit) {
        val path = Path().apply {
            moveTo(screenX, screenY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post { onComplete() }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.post { onComplete() }
            }
        }, null)
        if (!ok) {
            mainHandler.post { onComplete() }
        }
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()

    /**
     * 自訂繪製的編號準星視圖：外圈 + 十字準星 + 中心精準紅點 + 順序編號與次數標籤
     */
    private class TargetCrosshairView(
        context: Context,
        val number: Int,
        var clickCount: Int
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 15, 23, 42)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = dpF(2.2f)
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 56, 189, 248)
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.2f)
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F43F5E")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            textSize = dpF(13f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFEA00")
            textSize = dpF(9.5f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - dpF(2f)

            canvas.drawCircle(cx, cy, radius, bgPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)

            canvas.drawLine(cx - radius * 0.7f, cy, cx + radius * 0.7f, cy, crossPaint)
            canvas.drawLine(cx, cy - radius * 0.7f, cx, cy + radius * 0.7f, crossPaint)

            canvas.drawCircle(cx, cy, dpF(2.5f), centerDotPaint)

            canvas.drawText("#$number", cx, cy - dpF(4f), textPaint)
            canvas.drawText("×${clickCount}次", cx, cy + dpF(13f), subTextPaint)
        }

        private fun dpF(value: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                resources.displayMetrics
            )
    }
}
