package com.self.scheduledclicker

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 單一目標點位設定：
 * @param x 視窗 X 座標
 * @param y 視窗 Y 座標
 * @param clickCount 到達此點位時要連點幾次
 * @param delayAfterMs 點完此點位後，等待幾毫秒再前往下一個點位
 */
data class TargetPointConfig(
    var x: Int,
    var y: Int,
    var clickCount: Int = 1,
    var delayAfterMs: Long = 120L
)

/**
 * 負責儲存與管理所有點位座標、每個點位的點擊次數、循環次數、定時觸發時間與 NTP 校準狀態。
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
    var nextPointDelayMs: Long = 120L
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
        samePointIntervalMs = p.getLong(KEY_SAME_POINT_INTERVAL_MS, 65L).coerceIn(20L, 5000L)
        nextPointDelayMs = p.getLong(KEY_NEXT_POINT_DELAY_MS, 120L).coerceIn(20L, 10000L)

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
                            delayAfterMs = obj.optLong("delayAfterMs", nextPointDelayMs).coerceIn(20L, 10000L)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        if (points.isEmpty()) {
            // 預設建立 3 個順序點位 ① → ② → ③
            points.add(TargetPointConfig(x = 260, y = 450, clickCount = defaultClicksPerPoint, delayAfterMs = nextPointDelayMs))
            points.add(TargetPointConfig(x = 260, y = 620, clickCount = defaultClicksPerPoint, delayAfterMs = nextPointDelayMs))
            points.add(TargetPointConfig(x = 260, y = 790, clickCount = defaultClicksPerPoint, delayAfterMs = nextPointDelayMs))
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
            delayAfterMs = nextPointDelayMs
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
        applyToAllPoints: Boolean = true
    ) {
        defaultClicksPerPoint = clicksPerPoint.coerceIn(1, 999)
        totalCycles = cycles.coerceIn(1, 999)
        samePointIntervalMs = sameIntervalMs.coerceIn(20L, 5000L)
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
            }
            savePoints(context)
        }
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

    /**
     * 計算下一次目標觸發時間的精準 Epoch 毫秒數。
     * 若設定的時刻在今天尚未到達，則為今天；若已過期超過 500ms，則自動排程至明天同一時刻。
     */
    fun computeNextTriggerEpochMs(nowMs: Long = getAccurateTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, targetDeciSecond * 100)
        }
        var targetMs = cal.timeInMillis
        if (targetMs < nowMs - 500L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            targetMs = cal.timeInMillis
        }
        return targetMs
    }
}
