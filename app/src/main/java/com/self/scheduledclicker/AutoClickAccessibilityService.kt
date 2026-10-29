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
import android.graphics.Rect
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
import android.view.accessibility.AccessibilityNodeInfo
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
 * 3. 顯示可拖曳的編號準星（#1、#2、#3...）
 * 4. 防遮蔽隱身模式（Stealth Mode）：在點擊瞬間將所有準星移出螢幕範圍，避開 Android FilterTouchesWhenObscured，相容蝦皮等電商與安全結帳按鈕！
 * 5. 跳頁不斷線：忽略系統 onInterrupt 干擾，即使頁面跳轉、對話框彈出依然依序精準連點！
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
        super.onTaskRemoved(rootIntent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不讀取螢幕內容，確保最高執行效能與隱私安全
    }

    override fun onInterrupt() {
        // 重要修復：跳轉畫面、對話框跳出或輸入法彈出時，Android 系統會呼叫 onInterrupt()。
        // 此處絕對不能呼叫 stopAllExecution()，否則跳轉畫面時連點會被直接中斷！
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
        }, 50L)
    }

    fun stopAllExecution(showToast: Boolean = false) {
        clickSequenceToken++
        val wasRunning = currentState != EngineState.IDLE
        currentState = EngineState.IDLE
        restoreMarkersToOriginalPositions()
        setMarkersTouchPassthrough(false)
        updateControlPanelUi()
        broadcastState()
        if (showToast && wasRunning) {
            Toast.makeText(this, "⏹️ 已停止並恢復準星顯示", Toast.LENGTH_SHORT).show()
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

        val tvHeader = TextView(context).apply {
            text = "✥ 00:00:00.0 | 編輯準星中"
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        }
        tvClockAndStatus = tvHeader
        root.addView(tvHeader)

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
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ClickerPrefs.getPanelX(context)
            y = ClickerPrefs.getPanelY(context)
        }

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
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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

    /**
     * 防遮蔽關鍵功能：將所有準星暫時移出螢幕可視區域
     * 讓蝦皮等含有 filterTouchesWhenObscured 的敏感按鈕上方 100% 毫無遮擋！
     */
    /**
     * 防遮蔽極致模式：
     * 1. 使用 TYPE_ACCESSIBILITY_OVERLAY（受信任無障礙視窗）
     * 2. 在點擊期間將控制面板與所有準星移出可視區並設為 GONE / 寬高 0，
     *    讓螢幕處於完全無遮擋狀態，徹底避開蝦皮 filterTouchesWhenObscured！
     */
    private fun moveMarkersOffScreenForStealth() {
        val wm = windowManager ?: return
        for (h in markerHolders) {
            h.view.visibility = View.GONE
            h.params.x = -10000
            h.params.y = -10000
            h.params.width = 0
            h.params.height = 0
            try {
                wm.updateViewLayout(h.view, h.params)
            } catch (_: Exception) {}
        }
        val panel = controlPanelView
        if (panel != null) {
            panel.visibility = View.GONE
            controlPanelParams.x = -10000
            controlPanelParams.y = -10000
            controlPanelParams.width = 0
            controlPanelParams.height = 0
            try {
                wm.updateViewLayout(panel, controlPanelParams)
            } catch (_: Exception) {}
        }
    }

    /**
     * 點擊結束後將控制面板與準星恢復至原本設定的位置與尺寸
     */
    private fun restoreMarkersToOriginalPositions() {
        val wm = windowManager ?: return
        val sizePx = dp(46f)
        for (h in markerHolders) {
            h.view.visibility = View.VISIBLE
            h.params.x = h.config.x
            h.params.y = h.config.y
            h.params.width = sizePx
            h.params.height = sizePx
            try {
                wm.updateViewLayout(h.view, h.params)
            } catch (_: Exception) {}
        }
        val panel = controlPanelView
        if (panel != null) {
            panel.visibility = View.VISIBLE
            val savedX = ClickerPrefs.getPanelX(this)
            val savedY = ClickerPrefs.getPanelY(this)
            controlPanelParams.x = savedX
            controlPanelParams.y = savedY
            controlPanelParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            controlPanelParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            try {
                wm.updateViewLayout(panel, controlPanelParams)
            } catch (_: Exception) {}
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
     * 支援跳頁持續連點、防遮蔽移位（蝦皮結帳相容）
     */
    private fun executeMultiPointSequence(token: Int) {
        if (token != clickSequenceToken || currentState != EngineState.CLICKING) return

        data class ExecutionTarget(
            val index: Int,
            val centerX: Float,
            val centerY: Float,
            val clickCount: Int,
            val intervalMs: Long,
            val delayAfterMs: Long
        )

        // 1. 預先記錄所有點位中心點的真實螢幕像素座標
        val targets = mutableListOf<ExecutionTarget>()
        val loc = IntArray(2)
        val halfSize = dp(23f).toFloat() // 準星 46dp 的一半

        for (h in markerHolders) {
            val cx: Float
            val cy: Float
            if (h.view.isAttachedToWindow && h.view.width > 0) {
                h.view.getLocationOnScreen(loc)
                cx = loc[0] + h.view.width / 2f
                cy = loc[1] + h.view.height / 2f
            } else {
                cx = h.config.x + halfSize
                cy = h.config.y + halfSize
            }

            targets.add(
                ExecutionTarget(
                    index = h.index,
                    centerX = cx,
                    centerY = cy,
                    clickCount = h.config.clickCount.coerceAtLeast(1),
                    intervalMs = h.config.intervalMs.coerceAtLeast(10L),
                    delayAfterMs = h.config.delayAfterMs.coerceAtLeast(20L)
                )
            )
        }

        if (targets.isEmpty()) {
            stopAllExecution()
            return
        }

        // 2. 防遮蔽模式：在點擊期間將準星移出螢幕外，避免觸發蝦皮等 App 的 filterTouchesWhenObscured
        if (ClickerPrefs.isStealthMode) {
            moveMarkersOffScreenForStealth()
        }

        val totalCycles = ClickerPrefs.totalCycles.coerceAtLeast(1)
        val samePointInterval = ClickerPrefs.samePointIntervalMs.coerceAtLeast(20L)

        fun runStep(cycleIdx: Int, targetIdx: Int, clickIdx: Int) {
            if (token != clickSequenceToken || currentState != EngineState.CLICKING) {
                restoreMarkersToOriginalPositions()
                return
            }

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
            val pressDuration = ClickerPrefs.pressDurationMs.coerceIn(20L, 500L)
            performTapAt(target.centerX, target.centerY, durationMs = pressDuration) {
                if (token != clickSequenceToken || currentState != EngineState.CLICKING) {
                    restoreMarkersToOriginalPositions()
                    return@performTapAt
                }

                val nextClickIdx = clickIdx + 1
                if (nextClickIdx < target.clickCount) {
                    mainHandler.postDelayed({
                        runStep(cycleIdx, targetIdx, nextClickIdx)
                    }, target.intervalMs)
                } else {
                    val nextTargetIdx = targetIdx + 1
                    if (nextTargetIdx < targets.size) {
                        // 點下一個點位（此時如果換頁，target.delayAfterMs 提供了充足的跳頁載入時間）
                        mainHandler.postDelayed({
                            runStep(cycleIdx, nextTargetIdx, 0)
                        }, target.delayAfterMs)
                    } else {
                        // 點下一輪
                        mainHandler.postDelayed({
                            runStep(cycleIdx + 1, 0, 0)
                        }, target.delayAfterMs)
                    }
                }
            }
        }

        runStep(0, 0, 0)
    }

    /**
     * 雙管齊下穿透點擊：
     * 1. 嘗試使用無障礙語意節點點擊（ACTION_CLICK）直接觸發 View 的點擊事件（不受任何 Tapjacking 防護阻擋）
     * 2. 同步派發微位移手勢（moveTo + lineTo），產生完整 DOWN -> MOVE -> UP 觸控生命週期
     */
    private fun performTapAt(screenX: Float, screenY: Float, durationMs: Long = 60L, onComplete: () -> Unit) {
        // 先嘗試節點直點（穿透防護）
        tryPerformNodeClickAt(screenX, screenY)

        val path = Path().apply {
            moveTo(screenX, screenY)
            lineTo(screenX, screenY + 1f) // 微小移動 1px，避免某些 View 將單點視為無效觸控
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post { onComplete() }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.postDelayed({ onComplete() }, 80L)
            }
        }, null)
        if (!ok) {
            mainHandler.postDelayed({ onComplete() }, 80L)
        }
    }

        /**
     * 智能無障礙節點點擊：
     * 嚴格過濾節點，排除全螢幕容器與對話框半透明遮罩，避免誤觸 cancelOnTouchOutside 造成跳頁失敗！
     */
    private fun tryPerformNodeClickAt(screenX: Float, screenY: Float): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val x = screenX.toInt()
            val y = screenY.toInt()
            val targetRect = Rect()
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels

            fun isLikelyRealButton(node: AccessibilityNodeInfo): Boolean {
                if (!node.isClickable) return false
                node.getBoundsInScreen(targetRect)
                val w = targetRect.width()
                val h = targetRect.height()

                // 排除全螢幕大容器或半透明背景遮罩（避免誤觸 cancelOnTouchOutside 導致跳頁中斷退回原畫面）
                if (w >= screenW * 0.82f && h >= screenH * 0.35f) return false
                if (w <= 0 || h <= 0) return false

                val cls = node.className?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""

                val isButtonClass = cls.contains("Button", ignoreCase = true) ||
                        cls.contains("TextView", ignoreCase = true) ||
                        cls.contains("ImageView", ignoreCase = true) ||
                        cls.contains("CheckBox", ignoreCase = true) ||
                        cls.contains("RadioButton", ignoreCase = true)
                val hasText = text.isNotBlank() || desc.isNotBlank()
                val isReasonableSize = w <= screenW * 0.85f && h <= dp(130f)

                return isButtonClass || hasText || isReasonableSize
            }

            fun findClickableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                node.getBoundsInScreen(targetRect)
                if (!targetRect.contains(x, y)) {
                    return null
                }
                val count = node.childCount
                for (i in count - 1 downTo 0) {
                    val child = node.getChild(i)
                    val found = findClickableNode(child)
                    if (found != null) return found
                }
                if (isLikelyRealButton(node)) {
                    return node
                }
                return null
            }

            val targetNode = findClickableNode(root)
            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                false
            }
        } catch (_: Exception) {
            false
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
