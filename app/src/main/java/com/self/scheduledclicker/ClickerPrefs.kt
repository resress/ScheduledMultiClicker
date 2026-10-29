package com.self.scheduledclicker

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 單個目標點位設定：
 * @param x 螢幕 X 座標
 * @param y 螢幕 Y 座標
 * @param clickCount 到點時要連續點擊幾次
 * @param delayAfterMs 點完此點後，等待幾毫秒再執行下一點（若跳頁，建議設定 800~1800ms）
 * @param intervalMs 該點位每次連續點擊之間的時間間隔（毫秒）
 */
data class TargetPointConfig(
    var x: Int,
    var y: Int,
    var clickCount: Int = 1,
    var delayAfterMs: Long = 300L,
    var intervalMs: Long = 65L
)

/**
 * 負責儲存與管理所有點位座標、各點位的點擊次數、總輪數、定時觸發時間與 NTP 校準狀態。
 */
object ClickerPrefs {
    private const val PREF_NAME = "scheduled_multi_clicker_prefs"

    private const val KEY_TARGET_HOUR = "target_hour"
    private const val KEY_TARGET_MINUTE = "target_minute"
    private const val KEY_TARGET_SECOND = "target_second"
    private const val KEY_TARGET_DECI = "target_deci"

    private const val KEY_DEFAULT_CLICKS_PER_POINT = "default_clicks_per_point"
    private const val KEY_TOTAL_CYCLES = "total_cycles"
    private const val KEY_SAME_POINT_INTERVAL_MS = "same_point_interval_ms"
    private const val KEY_NEXT_POINT_DELAY_MS = "next_point_delay_ms"
    private const val KEY_POINTS_JSON = "points_json"
    private const val KEY_PANEL_X = "panel_x"
    private const val KEY_PANEL_Y = "panel_y"
    private const val KEY_STEALTH_MODE = "stealth_mode" // 防遮蔽模式（蝦皮/電商相容）
    private const val KEY_ADVANCE_OFFSET_MS = "advance_offset_ms" // 搶購提前發動量（毫秒）
    private const val KEY_PRESS_DURATION_MS = "press_duration_ms" // 單次點擊按壓停留時長

    @Volatile
    var isNtpSynced: Boolean = false
        private set

    @Volatile
    var baseNtpTimeMs: Long = 0L
        private set

    @Volatile
    var baseElapsedRealtimeMs: Long = 0L
        private set

    @Volatile
    var lastOffsetMs: Long = 0L
        private set

    @Volatile
    var lastRttMs: Long = 0L
        private set

    var targetHour: Int = 12
        private set
    var targetMinute: Int = 0
        private set
    var targetSecond: Int = 0
        private set
    var targetDeciSecond: Int = 0
        private set

    var defaultClicksPerPoint: Int = 1
        private set
    var totalCycles: Int = 1
        private set
    var samePointIntervalMs: Long = 65L
        private set
    var nextPointDelayMs: Long = 300L
        private set
    var isStealthMode: Boolean = true // 預設開啟防遮蔽模式，突破蝦皮等防點擊劫持
        private set
    var advanceOffsetMs: Long = 0L // 搶購提前量（毫秒偏移，例如提前 150ms 抵消網路延遲）
        private set
    var pressDurationMs: Long = 55L // 單次觸摸停留時長（毫秒）
        private set

    val points: MutableList<TargetPointConfig> = mutableListOf()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        val cal = Calendar.getInstance()
        targetHour = p.getInt(KEY_TARGET_HOUR, cal.get(Calendar.HOUR_OF_DAY))
        targetMinute = p.getInt(KEY_TARGET_MINUTE, (cal.get(Calendar.MINUTE) + 1) % 60)
        targetSecond = p.getInt(KEY_TARGET_SECOND, 0)
        targetDeciSecond = p.getInt(KEY_TARGET_DECI, 0)

        defaultClicksPerPoint = p.getInt(KEY_DEFAULT_CLICKS_PER_POINT, 1).coerceIn(1, 999)
        totalCycles = p.getInt(KEY_TOTAL_CYCLES, 1).coerceIn(1, 999)
        samePointIntervalMs = p.getLong(KEY_SAME_POINT_INTERVAL_MS, 65L).coerceIn(10L, 5000L)
        nextPointDelayMs = p.getLong(KEY_NEXT_POINT_DELAY_MS, 300L).coerceIn(20L, 10000L)
        isStealthMode = p.getBoolean(KEY_STEALTH_MODE, true)
        advanceOffsetMs = p.getLong(KEY_ADVANCE_OFFSET_MS, 0L).coerceIn(0L, 5000L)
        pressDurationMs = p.getLong(KEY_PRESS_DURATION_MS, 55L).coerceIn(20L, 500L)

        loadPoints(context)
    }

    private fun loadPoints(context: Context) {
        val raw = prefs(context).getString(KEY_POINTS_JSON, null)
        points.clear()
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    points.add(
                        TargetPointConfig(
                            x = obj.optInt("x", 300),
                            y = obj.optInt("y", 400 + i * 130),
                            clickCount = obj.optInt("clickCount", defaultClicksPerPoint).coerceIn(1, 999),
                            delayAfterMs = obj.optLong("delayAfterMs", nextPointDelayMs).coerceIn(20L, 10000L),
                            intervalMs = obj.optLong("intervalMs", samePointIntervalMs).coerceIn(10L, 5000L)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        if (points.isEmpty()) {
            // 預設建立 3 個順序點位 #1 → #2 → #3
            points.add(TargetPointConfig(x = 260, y = 450, clickCount = defaultClicksPerPoint, delayAfterMs = 600L, intervalMs = 65L))
            points.add(TargetPointConfig(x = 260, y = 620, clickCount = defaultClicksPerPoint, delayAfterMs = 1800L, intervalMs = 65L))
            points.add(TargetPointConfig(x = 260, y = 790, clickCount = defaultClicksPerPoint, delayAfterMs = nextPointDelayMs, intervalMs = 65L))
            savePoints(context)
        }
    }

    fun savePoints(context: Context) {
        val arr = JSONArray()
        for (pt in points) {
            val obj = JSONObject().apply {
                put("x", pt.x)
                put("y", pt.y)
                put("clickCount", pt.clickCount)
                put("delayAfterMs", pt.delayAfterMs)
                put("intervalMs", pt.intervalMs)
            }
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_POINTS_JSON, arr.toString()).apply()
    }

    fun addNewPoint(context: Context): TargetPointConfig {
        val idx = points.size
        val baseX = points.lastOrNull()?.x ?: 260
        val baseY = (points.lastOrNull()?.y ?: 350) + 120
        val newPt = TargetPointConfig(
            x = (baseX + (idx % 3) * 40).coerceIn(60, 800),
            y = baseY.coerceIn(160, 1600),
            clickCount = defaultClicksPerPoint,
            delayAfterMs = nextPointDelayMs,
            intervalMs = samePointIntervalMs
        )
        points.add(newPt)
        savePoints(context)
        return newPt
    }

    fun removeLastPoint(context: Context): Boolean {
        if (points.size <= 1) return false
        points.removeAt(points.lastIndex)
        savePoints(context)
        return true
    }

    fun setTargetTime(context: Context, hour: Int, minute: Int, second: Int, deciSecond: Int) {
        targetHour = hour.coerceIn(0, 23)
        targetMinute = minute.coerceIn(0, 59)
        targetSecond = second.coerceIn(0, 59)
        targetDeciSecond = deciSecond.coerceIn(0, 9)
        prefs(context).edit()
            .putInt(KEY_TARGET_HOUR, targetHour)
            .putInt(KEY_TARGET_MINUTE, targetMinute)
            .putInt(KEY_TARGET_SECOND, targetSecond)
            .putInt(KEY_TARGET_DECI, targetDeciSecond)
            .apply()
    }

    fun setSequenceConfig(
        context: Context,
        clicksPerPoint: Int,
        cycles: Int,
        sameIntervalMs: Long,
        nextDelayMs: Long,
        applyToAllPoints: Boolean = false
    ) {
        defaultClicksPerPoint = clicksPerPoint.coerceIn(1, 999)
        totalCycles = cycles.coerceIn(1, 999)
        samePointIntervalMs = sameIntervalMs.coerceIn(10L, 5000L)
        nextPointDelayMs = nextDelayMs.coerceIn(20L, 10000L)

        prefs(context).edit()
            .putInt(KEY_DEFAULT_CLICKS_PER_POINT, defaultClicksPerPoint)
            .putInt(KEY_TOTAL_CYCLES, totalCycles)
            .putLong(KEY_SAME_POINT_INTERVAL_MS, samePointIntervalMs)
            .putLong(KEY_NEXT_POINT_DELAY_MS, nextPointDelayMs)
            .apply()

        if (applyToAllPoints) {
            for (pt in points) {
                pt.clickCount = defaultClicksPerPoint
                pt.delayAfterMs = nextPointDelayMs
                pt.intervalMs = samePointIntervalMs
            }
            savePoints(context)
        }
    }

    fun setStealthMode(context: Context, enabled: Boolean) {
        isStealthMode = enabled
        prefs(context).edit().putBoolean(KEY_STEALTH_MODE, enabled).apply()
    }

    fun setAdvanceOffsetMs(context: Context, offsetMs: Long) {
        advanceOffsetMs = offsetMs.coerceIn(0L, 5000L)
        prefs(context).edit().putLong(KEY_ADVANCE_OFFSET_MS, advanceOffsetMs).apply()
    }

    fun setPressDurationMs(context: Context, durationMs: Long) {
        pressDurationMs = durationMs.coerceIn(20L, 500L)
        prefs(context).edit().putLong(KEY_PRESS_DURATION_MS, pressDurationMs).apply()
    }

    fun savePanelPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_PANEL_X, x)
            .putInt(KEY_PANEL_Y, y)
            .apply()
    }

    fun getPanelX(context: Context): Int = prefs(context).getInt(KEY_PANEL_X, 24)
    fun getPanelY(context: Context): Int = prefs(context).getInt(KEY_PANEL_Y, 110)

    fun updateNtpSync(ntpTimeMs: Long, elapsedRealtimeMs: Long, rttMs: Long) {
        baseNtpTimeMs = ntpTimeMs
        baseElapsedRealtimeMs = elapsedRealtimeMs
        lastRttMs = rttMs
        val currentSys = System.currentTimeMillis()
        val currentNtp = ntpTimeMs + (SystemClock.elapsedRealtime() - elapsedRealtimeMs)
        lastOffsetMs = currentNtp - currentSys
        isNtpSynced = true
    }

    fun getAccurateTimeMillis(): Long {
        return if (isNtpSynced) {
            baseNtpTimeMs + (SystemClock.elapsedRealtime() - baseElapsedRealtimeMs)
        } else {
            System.currentTimeMillis()
        }
    }

    fun formatTargetTimeString(): String =
        String.format("%02d:%02d:%02d.%d", targetHour, targetMinute, targetSecond, targetDeciSecond)

    fun formatActualTriggerTimeString(): String {
        val nextMs = computeNextTriggerEpochMs()
        val cal = Calendar.getInstance().apply { timeInMillis = nextMs }
        val deci = (cal.get(Calendar.MILLISECOND) / 100)
        val base = String.format("%02d:%02d:%02d.%d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND), deci)
        return if (advanceOffsetMs > 0L) "$base (提前${advanceOffsetMs}ms)" else base
    }

    fun computeNextTriggerEpochMs(nowMs: Long = getAccurateTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, targetDeciSecond * 100)
        }
        var targetMs = cal.timeInMillis - advanceOffsetMs
        if (targetMs < nowMs - 500L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            targetMs = cal.timeInMillis - advanceOffsetMs
        }
        return targetMs
    }
}
