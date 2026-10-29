package com.self.scheduledclicker

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    private lateinit var tvLiveClock: TextView
    private lateinit var tvNtpInfo: TextView
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnAccessPerm: Button
    private lateinit var btnToggleOverlay: Button

    private lateinit var etHour: EditText
    private lateinit var etMinute: EditText
    private lateinit var etSecond: EditText
    private lateinit var etDeci: EditText
    private lateinit var tvTargetSummary: TextView

    private lateinit var etClicksPerPoint: EditText
    private lateinit var etTotalCycles: EditText
    private lateinit var etSameInterval: EditText
    private lateinit var etNextDelay: EditText
    private lateinit var swStealthMode: Switch
    private lateinit var pointsContainer: LinearLayout

    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLooping = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatusAndPointsUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClickerPrefs.init(this)
        requestNotificationPermissionIfNeeded()
        setContentView(buildMainView())
        populateInputsFromPrefs()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AutoClickAccessibilityService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        isLooping = true
        Choreographer.getInstance().postFrameCallback(this)
        refreshStatusAndPointsUi()
        mainHandler.postDelayed({ refreshStatusAndPointsUi() }, 500L)
    }

    override fun onPause() {
        super.onPause()
        isLooping = false
        Choreographer.getInstance().removeFrameCallback(this)
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isLooping) return
        val now = ClickerPrefs.getAccurateTimeMillis()
        val full = clockFormat.format(Date(now))
        tvLiveClock.text = full.substring(0, 10) // HH:mm:ss.S
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun isAccessibilityEnabledInSystem(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName, ignoreCase = true)
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    private fun buildMainView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16f)
            setPadding(pad, dp(20f), pad, dp(36f))
        }
        scroll.addView(root)

        // 標題
        val tvTitle = TextView(this).apply {
            text = "🎯 定時多點依序連點器"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub = TextView(this).apply {
            text = "支援跳頁不中斷 · 蝦皮等電商防遮蔽模式 · 0.1秒高精準定時"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(4f), 0, dp(14f))
        }
        root.addView(tvTitle)
        root.addView(tvSub)

        // ================= 卡片 1：即時時間、權限檢查與懸浮窗開關 =================
        val cardPerm = createCard()
        tvLiveClock = TextView(this).apply {
            text = "00:00:00.0"
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            gravity = Gravity.CENTER
        }
        tvNtpInfo = TextView(this).apply {
            text = "時源狀態載入中..."
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2f), 0, dp(12f))
        }
        cardPerm.addView(tvLiveClock)
        cardPerm.addView(tvNtpInfo)

        btnOverlayPerm = createActionButton("⚠️ 步驟 1：點此開啟「懸浮視窗權限」", "#334155", Color.WHITE) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        cardPerm.addView(btnOverlayPerm)

        btnAccessPerm = createActionButton("⚠️ 步驟 2：點此開啟「無障礙自動點擊服務」", "#334155", Color.WHITE) {
            val sysOn = isAccessibilityEnabledInSystem()
            val bound = AutoClickAccessibilityService.instance != null
            if (sysOn && !bound) {
                Toast.makeText(
                    this,
                    "提示：請將無障礙開關「先關掉、再打開一次」即可恢復連線！",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "請在無障礙設定中找到「定時多點連點器」並開啟開關",
                    Toast.LENGTH_LONG
                ).show()
            }
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8f)
        }
        cardPerm.addView(btnAccessPerm)

        btnToggleOverlay = createActionButton("🚀 啟動懸浮控制面板與編號準星", "#00E676", Color.parseColor("#0F172A")) {
            handleToggleOverlay()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10f)
        }
        cardPerm.addView(btnToggleOverlay)
        root.addView(cardPerm)

        // ================= 卡片 2：設定定時觸發時間 (HH:mm:ss.S) =================
        val cardTime = createCard()
        cardTime.addView(createSectionTitle("⏰ 1. 設定自動開點時間 (24小時制)"))

        tvTargetSummary = TextView(this).apply {
            text = "目前設定觸發時刻：12:00:00.0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8f))
        }
        cardTime.addView(tvTargetSummary)

        val timeInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etHour = createNumberEdit()
        etMinute = createNumberEdit()
        etSecond = createNumberEdit()
        etDeci = createNumberEdit()

        timeInputRow.addView(createLabeledInput("時(0-23)", etHour))
        timeInputRow.addView(createLabeledInput("分(0-59)", etMinute))
        timeInputRow.addView(createLabeledInput("秒(0-59)", etSecond))
        timeInputRow.addView(createLabeledInput("0.1秒(0-9)", etDeci))
        cardTime.addView(timeInputRow)

        val quickTimeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(8f))
        }
        val btnAfter15s = createSmallBtn("⚡ 設為 15 秒後(測試)", "#334155") {
            val future = ClickerPrefs.getAccurateTimeMillis() + 15_000L
            val cal = Calendar.getInstance().apply { timeInMillis = future }
            ClickerPrefs.setTargetTime(
                this,
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND),
                0
            )
            populateInputsFromPrefs()
            Toast.makeText(this, "已設為 15 秒後 (${ClickerPrefs.formatTargetTimeString()})", Toast.LENGTH_SHORT).show()
        }
        val btnNextMin = createSmallBtn("🕐 設為下一整分", "#334155") {
            val now = ClickerPrefs.getAccurateTimeMillis()
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.MINUTE, 1)
                set(Calendar.SECOND, 0)
            }
            ClickerPrefs.setTargetTime(
                this,
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                0,
                0
            )
            populateInputsFromPrefs()
            Toast.makeText(this, "已設為下一整分 (${ClickerPrefs.formatTargetTimeString()})", Toast.LENGTH_SHORT).show()
        }
        val btnNtpSync = createSmallBtn("🌐 NTP網路校時", "#2563EB") {
            performNtpSync()
        }
        quickTimeRow.addView(btnAfter15s)
        quickTimeRow.addView(btnNextMin)
        quickTimeRow.addView(btnNtpSync)
        cardTime.addView(quickTimeRow)

        val tvAdvanceDesc = TextView(this).apply {
            text = "🚀 搶購提前發動（抵消網路延遲與伺服器RTT）：目前提前 ${ClickerPrefs.advanceOffsetMs}ms\n實際發動時刻：${ClickerPrefs.formatActualTriggerTimeString()}"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4f), 0, dp(4f))
        }
        cardTime.addView(tvAdvanceDesc)

        val advanceBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2f), 0, dp(8f))
        }
        val btnAdv0 = createSmallBtn("0ms(準點)", if (ClickerPrefs.advanceOffsetMs == 0L) "#0284C7" else "#1E293B") {
            ClickerPrefs.setAdvanceOffsetMs(this, 0L)
            refreshStatusAndPointsUi()
        }
        val btnAdv50 = createSmallBtn("50ms", if (ClickerPrefs.advanceOffsetMs == 50L) "#0284C7" else "#1E293B") {
            ClickerPrefs.setAdvanceOffsetMs(this, 50L)
            refreshStatusAndPointsUi()
        }
        val btnAdv100 = createSmallBtn("100ms", if (ClickerPrefs.advanceOffsetMs == 100L) "#0284C7" else "#1E293B") {
            ClickerPrefs.setAdvanceOffsetMs(this, 100L)
            refreshStatusAndPointsUi()
        }
        val btnAdv150 = createSmallBtn("150ms(推薦)", if (ClickerPrefs.advanceOffsetMs == 150L) "#059669" else "#1E293B") {
            ClickerPrefs.setAdvanceOffsetMs(this, 150L)
            refreshStatusAndPointsUi()
        }
        val btnAdv200 = createSmallBtn("200ms", if (ClickerPrefs.advanceOffsetMs == 200L) "#0284C7" else "#1E293B") {
            ClickerPrefs.setAdvanceOffsetMs(this, 200L)
            refreshStatusAndPointsUi()
        }
        val btnAdvCustom = createSmallBtn("✏️自訂", "#334155") {
            showNumberInputDialog("設定搶購提前量", ClickerPrefs.advanceOffsetMs, 0L, 3000L, "ms") { v ->
                ClickerPrefs.setAdvanceOffsetMs(this, v)
                refreshStatusAndPointsUi()
            }
        }
        advanceBtnRow.addView(btnAdv0)
        advanceBtnRow.addView(btnAdv50)
        advanceBtnRow.addView(btnAdv100)
        advanceBtnRow.addView(btnAdv150)
        advanceBtnRow.addView(btnAdv200)
        advanceBtnRow.addView(btnAdvCustom)
        cardTime.addView(advanceBtnRow)

        val btnSaveTime = createActionButton("💾 儲存觸發時間設定", "#38BDF8", Color.parseColor("#0F172A")) {
            saveTimeAndSequenceSettings(showToast = true, updateMarkersIfShowing = true)
        }
        cardTime.addView(btnSaveTime)
        root.addView(cardTime)

        // ================= 卡片 3：點位順序與點擊次數設定 =================
        val cardSeq = createCard()
        cardSeq.addView(createSectionTitle("🔢 2. 點選次數、間隔與防遮蔽相容設定"))

        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8f))
        }
        etClicksPerPoint = createNumberEdit()
        etTotalCycles = createNumberEdit()
        gridRow1.addView(createLabeledInput("每點位預設連點", etClicksPerPoint))
        gridRow1.addView(createLabeledInput("整組順序循環幾輪", etTotalCycles))
        cardSeq.addView(gridRow1)

        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10f))
        }
        etSameInterval = createNumberEdit()
        etNextDelay = createNumberEdit()
        gridRow2.addView(createLabeledInput("同點連點間隔(ms)", etSameInterval))
        gridRow2.addView(createLabeledInput("預設切換下一點(ms)", etNextDelay))
        cardSeq.addView(gridRow2)

        swStealthMode = Switch(this).apply {
            text = "🛡️ 蝦皮/電商防遮蔽模式（突破 FilterTouchesWhenObscured）"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = ClickerPrefs.isStealthMode
            setPadding(0, dp(4f), 0, dp(10f))
            setOnCheckedChangeListener { _, checked ->
                ClickerPrefs.setStealthMode(this@MainActivity, checked)
                val msg = if (checked) "已開啟防遮蔽模式（連點瞬間暫時移開準星，蝦皮結帳按鈕可正常觸發）" else "已關閉防遮蔽模式"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        cardSeq.addView(swStealthMode)

        val tvStealthHint = TextView(this).apply {
            text = "💡 原理解說：蝦皮結帳等畫面會偵測上方是否有懸浮窗遮擋（Tapjacking 防護）。開啟此模式後，連點瞬間會自動將準星移出遮擋區，點完自動還原，100% 正常點擊！"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, 0, 0, dp(12f))
        }
        cardSeq.addView(tvStealthHint)

        val btnSaveSeq = createActionButton("💾 套用次數與間隔設定", "#00E676", Color.parseColor("#0F172A")) {
            saveTimeAndSequenceSettings(showToast = true, updateMarkersIfShowing = true)
        }
        cardSeq.addView(btnSaveSeq)

        val tvPointListHeader = TextView(this).apply {
            text = "📍 各點位獨立設定（提示：若點完該點會「跳轉新畫面」，請設定較長的換頁等待時間，例如 800~1500ms 等畫面載入完成）："
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16f), 0, dp(6f))
        }
        cardSeq.addView(tvPointListHeader)

        pointsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        cardSeq.addView(pointsContainer)

        val ptManageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
        }
        val btnAddPtMain = createSmallBtn("➕ 新增一個點位", "#334155") {
            ClickerPrefs.addNewPoint(this)
            if (AutoClickAccessibilityService.isOverlayShowing) {
                AutoClickAccessibilityService.instance?.rebuildTargetMarkers()
            }
            refreshStatusAndPointsUi()
        }
        val btnRemPtMain = createSmallBtn("➖ 移除最後點位", "#334155") {
            if (ClickerPrefs.removeLastPoint(this)) {
                if (AutoClickAccessibilityService.isOverlayShowing) {
                    AutoClickAccessibilityService.instance?.rebuildTargetMarkers()
                }
                refreshStatusAndPointsUi()
            } else {
                Toast.makeText(this, "至少需保留 1 個點位", Toast.LENGTH_SHORT).show()
            }
        }
        ptManageRow.addView(btnAddPtMain)
        ptManageRow.addView(btnRemPtMain)
        cardSeq.addView(ptManageRow)

        root.addView(cardSeq)
        return scroll
    }

    private fun populateInputsFromPrefs() {
        etHour.setText(ClickerPrefs.targetHour.toString())
        etMinute.setText(ClickerPrefs.targetMinute.toString())
        etSecond.setText(ClickerPrefs.targetSecond.toString())
        etDeci.setText(ClickerPrefs.targetDeciSecond.toString())

        etClicksPerPoint.setText(ClickerPrefs.defaultClicksPerPoint.toString())
        etTotalCycles.setText(ClickerPrefs.totalCycles.toString())
        etSameInterval.setText(ClickerPrefs.samePointIntervalMs.toString())
        etNextDelay.setText(ClickerPrefs.nextPointDelayMs.toString())
        swStealthMode.isChecked = ClickerPrefs.isStealthMode
        tvTargetSummary.text = "設定時刻：${ClickerPrefs.formatTargetTimeString()}  →  實際發動：${ClickerPrefs.formatActualTriggerTimeString()}"
    }

    private fun saveTimeAndSequenceSettings(showToast: Boolean, updateMarkersIfShowing: Boolean) {
        val hr = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: ClickerPrefs.targetHour
        val min = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: ClickerPrefs.targetMinute
        val sec = etSecond.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: ClickerPrefs.targetSecond
        val deci = etDeci.text.toString().toIntOrNull()?.coerceIn(0, 9) ?: ClickerPrefs.targetDeciSecond
        ClickerPrefs.setTargetTime(this, hr, min, sec, deci)

        val clicks = etClicksPerPoint.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
        val cycles = etTotalCycles.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
        val sameMs = etSameInterval.text.toString().toLongOrNull()?.coerceIn(20L, 5000L) ?: 65L
        val nextMs = etNextDelay.text.toString().toLongOrNull()?.coerceIn(20L, 10000L) ?: 300L

        ClickerPrefs.setSequenceConfig(
            context = this,
            clicksPerPoint = clicks,
            cycles = cycles,
            sameIntervalMs = sameMs,
            nextDelayMs = nextMs,
            applyToAllPoints = false
        )

        populateInputsFromPrefs()
        if (updateMarkersIfShowing && AutoClickAccessibilityService.isOverlayShowing) {
            AutoClickAccessibilityService.instance?.rebuildTargetMarkers()
        }
        refreshStatusAndPointsUi()

        if (showToast) {
            Toast.makeText(
                this,
                "✅ 設定已儲存！時間 ${ClickerPrefs.formatTargetTimeString()}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleToggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先完成步驟 1：開啟懸浮視窗權限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val svc = AutoClickAccessibilityService.instance
        if (svc == null) {
            val sysOn = isAccessibilityEnabledInSystem()
            val msg = if (sysOn) {
                "無障礙開關雖已開啟，但服務曾被系統強制中斷。請點進「定時多點連點器」將開關「先關再開」即可恢復！"
            } else {
                "請先完成步驟 2：開啟「定時多點連點器」無障礙服務"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        saveTimeAndSequenceSettings(showToast = false, updateMarkersIfShowing = false)
        if (AutoClickAccessibilityService.isOverlayShowing) {
            svc.hideOverlay()
        } else {
            svc.showOrUpdateOverlay()
        }
        refreshStatusAndPointsUi()
    }

    private fun performNtpSync() {
        tvNtpInfo.text = "⏳ 正在與台灣標準時間 (time.stdtime.gov.tw) 同步..."
        thread(start = true) {
            val res = SntpClient.syncTimeBestOf("time.stdtime.gov.tw", samples = 3, timeoutMs = 2500)
            mainHandler.post {
                res.onSuccess { ntp ->
                    ClickerPrefs.updateNtpSync(ntp.ntpTimeMs, ntp.elapsedRealtimeMs, ntp.roundTripTimeMs)
                    refreshStatusAndPointsUi()
                    val sign = if (ClickerPrefs.lastOffsetMs >= 0) "+" else ""
                    Toast.makeText(
                        this,
                        "✅ NTP 校時成功！偏差 ${sign}${ClickerPrefs.lastOffsetMs}ms",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    refreshStatusAndPointsUi()
                    Toast.makeText(this, "⚠️ NTP 校時失敗，使用系統時間", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatusAndPointsUi() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val isServiceBound = AutoClickAccessibilityService.instance != null
        val isSysSettingOn = isAccessibilityEnabledInSystem()

        if (hasOverlay) {
            btnOverlayPerm.text = "✅ 步驟 1：懸浮視窗權限（已開啟）"
            btnOverlayPerm.background = rounded("#1E293B", 8f, "#00E676")
            btnOverlayPerm.setTextColor(Color.parseColor("#00E676"))
        } else {
            btnOverlayPerm.text = "⚠️ 步驟 1：點此開啟「懸浮視窗權限」"
            btnOverlayPerm.background = rounded("#F59E0B", 8f)
            btnOverlayPerm.setTextColor(Color.parseColor("#0F172A"))
        }

        if (isServiceBound) {
            btnAccessPerm.text = "✅ 步驟 2：無障礙自動點擊服務（已啟動連線）"
            btnAccessPerm.background = rounded("#1E293B", 8f, "#00E676")
            btnAccessPerm.setTextColor(Color.parseColor("#00E676"))
        } else if (isSysSettingOn) {
            btnAccessPerm.text = "🔄 步驟 2：系統已開但服務被中斷（點此「關→開」重連）"
            btnAccessPerm.background = rounded("#38BDF8", 8f)
            btnAccessPerm.setTextColor(Color.parseColor("#0F172A"))
        } else {
            btnAccessPerm.text = "⚠️ 步驟 2：點此開啟「無障礙自動點擊服務」"
            btnAccessPerm.background = rounded("#F59E0B", 8f)
            btnAccessPerm.setTextColor(Color.parseColor("#0F172A"))
        }

        if (AutoClickAccessibilityService.isOverlayShowing) {
            btnToggleOverlay.text = "⏹️ 隱藏懸浮控制面板與準星（執行中）"
            btnToggleOverlay.background = rounded("#EF4444", 10f)
            btnToggleOverlay.setTextColor(Color.WHITE)
        } else {
            btnToggleOverlay.text = "🚀 啟動懸浮控制面板與編號準星 (#1, #2, #3...)"
            btnToggleOverlay.background = rounded("#00E676", 10f)
            btnToggleOverlay.setTextColor(Color.parseColor("#0F172A"))
        }

        tvNtpInfo.text = if (ClickerPrefs.isNtpSynced) {
            val sign = if (ClickerPrefs.lastOffsetMs >= 0) "+" else ""
            "✅ 已同步台灣標準時間 (修正 ${sign}${ClickerPrefs.lastOffsetMs}ms · 延遲 ${ClickerPrefs.lastRttMs}ms)"
        } else {
            "📱 目前使用手機系統時間（可點下方按鈕同步 NTP 標準時間）"
        }

        tvTargetSummary.text = "設定時刻：${ClickerPrefs.formatTargetTimeString()}  →  實際發動：${ClickerPrefs.formatActualTriggerTimeString()}"

        // 更新點位微調清單（含次數與跳頁等待延遲）
        pointsContainer.removeAllViews()
        for ((idx, pt) in ClickerPrefs.points.withIndex()) {
            val num = idx + 1
            val cardItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(10f)
                setPadding(pad, pad, pad, pad)
                background = rounded("#0F172A", 10f, "#334155")
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8f)
                }
                layoutParams = lp
            }

            // 1. 第一行：點位編號 + 連點次數 + 次數調整按鈕
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvLabel = TextView(this).apply {
                text = "📍 點位 #$num  →  連點 ${pt.clickCount} 次"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnMinus = createSmallBtn("-1", "#334155") {
                pt.clickCount = (pt.clickCount - 1).coerceAtLeast(1)
                ClickerPrefs.savePoints(this)
                if (AutoClickAccessibilityService.isOverlayShowing) {
                    AutoClickAccessibilityService.instance?.rebuildTargetMarkers()
                }
                refreshStatusAndPointsUi()
            }
            val btnPlus = createSmallBtn("+1", "#334155") {
                pt.clickCount = (pt.clickCount + 1).coerceAtMost(999)
                ClickerPrefs.savePoints(this)
                if (AutoClickAccessibilityService.isOverlayShowing) {
                    AutoClickAccessibilityService.instance?.rebuildTargetMarkers()
                }
                refreshStatusAndPointsUi()
            }
            val btnCustomCount = createSmallBtn("✏️次數", "#334155") {
                showNumberInputDialog("設定點位 #$num 連點次數", pt.clickCount.toLong(), 1L, 999L, "次") { v ->
                    pt.clickCount = v.toInt()
                    ClickerPrefs.savePoints(this)
                    if (AutoClickAccessibilityService.isOverlayShowing) {
                        AutoClickAccessibilityService.instance?.rebuildTargetMarkers()
                    }
                    refreshStatusAndPointsUi()
                }
            }

            rowTop.addView(tvLabel)
            rowTop.addView(btnMinus)
            rowTop.addView(btnPlus)
            rowTop.addView(btnCustomCount)
            cardItem.addView(rowTop)

            // 2. 第二行：每一次點擊之間的間隔 (連點間隔 intervalMs)
            val rowInterval = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, 0)
            }
            val tvIntervalDesc = TextView(this).apply {
                text = "每次點擊間隔: ${pt.intervalMs}ms"
                setTextColor(Color.parseColor("#F59E0B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnInt35 = createSmallBtn("35ms", if (pt.intervalMs == 35L) "#0284C7" else "#1E293B") {
                pt.intervalMs = 35L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnInt65 = createSmallBtn("65ms(推薦)", if (pt.intervalMs == 65L) "#059669" else "#1E293B") {
                pt.intervalMs = 65L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnInt100 = createSmallBtn("100ms", if (pt.intervalMs == 100L) "#0284C7" else "#1E293B") {
                pt.intervalMs = 100L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnIntCustom = createSmallBtn("✏️自訂", "#334155") {
                showNumberInputDialog("設定點位 #$num 每次點擊間隔", pt.intervalMs, 10L, 3000L, "ms") { v ->
                    pt.intervalMs = v
                    ClickerPrefs.savePoints(this)
                    refreshStatusAndPointsUi()
                }
            }
            rowInterval.addView(tvIntervalDesc)
            rowInterval.addView(btnInt35)
            rowInterval.addView(btnInt65)
            rowInterval.addView(btnInt100)
            rowInterval.addView(btnIntCustom)
            cardItem.addView(rowInterval)

            // 3. 第三行：點完此點後等待時間 (換點 / 跳頁延遲 delayAfterMs)
            val rowBottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, 0)
            }

            val isPageJump = pt.delayAfterMs >= 600L
            val jumpTag = if (isPageJump) " [⏳跳頁等待]" else ""
            val tvDelayDesc = TextView(this).apply {
                text = "點完後等待: ${pt.delayAfterMs}ms$jumpTag"
                setTextColor(if (isPageJump) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDelay100 = createSmallBtn("100ms", if (pt.delayAfterMs == 100L) "#0284C7" else "#1E293B") {
                pt.delayAfterMs = 100L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnDelay300 = createSmallBtn("300ms", if (pt.delayAfterMs == 300L) "#0284C7" else "#1E293B") {
                pt.delayAfterMs = 300L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnDelay800 = createSmallBtn("800ms", if (pt.delayAfterMs == 800L) "#0284C7" else "#1E293B") {
                pt.delayAfterMs = 800L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnDelay1800 = createSmallBtn("1.8秒(跳頁)", if (pt.delayAfterMs == 1800L) "#059669" else "#1E293B") {
                pt.delayAfterMs = 1800L
                ClickerPrefs.savePoints(this)
                refreshStatusAndPointsUi()
            }
            val btnDelayCustom = createSmallBtn("✏️自訂", "#334155") {
                showNumberInputDialog("設定點位 #$num 點完後等待時間", pt.delayAfterMs, 20L, 10000L, "ms") { v ->
                    pt.delayAfterMs = v
                    ClickerPrefs.savePoints(this)
                    refreshStatusAndPointsUi()
                }
            }

            rowBottom.addView(tvDelayDesc)
            rowBottom.addView(btnDelay100)
            rowBottom.addView(btnDelay300)
            rowBottom.addView(btnDelay800)
            rowBottom.addView(btnDelay1800)
            rowBottom.addView(btnDelayCustom)
            cardItem.addView(rowBottom)

            pointsContainer.addView(cardItem)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun showNumberInputDialog(
        title: String,
        currentValue: Long,
        minVal: Long,
        maxVal: Long,
        unit: String,
        onConfirmed: (Long) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
            setSelectAllOnFocus(true)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setTextColor(Color.WHITE)
            setBackground(rounded("#1E293B", 8f, "#475569"))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("請輸入數值 ($minVal ~ $maxVal $unit)：")
            .setView(container)
            .setPositiveButton("確定") { _, _ ->
                val v = input.text.toString().toLongOrNull() ?: currentValue
                val clamped = v.coerceIn(minVal, maxVal)
                onConfirmed(clamped)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(15f)
            setPadding(pad, pad, pad, pad)
            background = rounded("#1E293B", 14f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14f)
            }
            layoutParams = lp
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8f))
        }
    }

    private fun createNumberEdit(): EditText {
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = rounded("#0F172A", 8f, "#475569")
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
        }
    }

    private fun createLabeledInput(label: String, editText: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            }
            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, 0, 0, dp(4f))
            }
            addView(tv)
            addView(editText)
        }
    }

    private fun createActionButton(
        textStr: String,
        bgHex: String,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = textStr
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(bgHex, 10f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun createSmallBtn(textStr: String, bgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(7f), dp(5f), dp(7f), dp(5f))
            background = rounded(bgHex, 6f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4f)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun rounded(bgHex: String, radiusDp: Float, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1.2f), Color.parseColor(strokeHex))
            }
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
}
