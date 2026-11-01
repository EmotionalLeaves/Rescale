package app.rescale.engine.pipeline

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Called after every tile (on the engine thread). Sleeping there is how we throttle: the
 * native loop simply waits before starting the next tile.
 *
 * - Thermal status MODERATE: short pauses; SEVERE or worse: long pauses.
 * - Thermal headroom close to the limit (API 30+): small pre-emptive pauses.
 * - Gentle mode, or battery < 20 % and not charging: a pause after every tile.
 */
class Throttle(context: Context, private val gentle: Boolean) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private var lastCheck = 0L
    private var delayMs = 0L

    /** True while the last decision included a cooling pause (for the notification). */
    var cooling = false
        private set

    fun afterTile() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck > 2_000) {
            lastCheck = now
            delayMs = decide()
        }
        if (delayMs > 0) Thread.sleep(delayMs)
    }

    private fun decide(): Long {
        var d = 0L
        cooling = false
        if (Build.VERSION.SDK_INT >= 29) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> Unit
                PowerManager.THERMAL_STATUS_MODERATE -> { d = 400; cooling = true }
                else -> { d = 3_000; cooling = true }
            }
        }
        if (Build.VERSION.SDK_INT >= 30 && d == 0L) {
            val headroom = power.getThermalHeadroom(10)
            if (!headroom.isNaN() && headroom >= 0.9f) { d = 200; cooling = true }
        }
        if (gentle) d = maxOf(d, 120)
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (level >= 0 && level * 100 / scale < 20 && !plugged) d = maxOf(d, 500)
        return d
    }
}

/** ETA from an exponential moving average of tile times (adapts when throttling kicks in). */
class EtaEstimator {
    private var last = 0L
    private var ema = 0.0
    private var samples = 0

    fun start() {
        last = SystemClock.elapsedRealtime()
        samples = 0
    }

    /** Returns seconds left, or null until a few tiles have been timed. */
    fun update(done: Int, total: Int): Long? {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - last).toDouble()
        last = now
        ema = if (samples == 0) dt else 0.2 * dt + 0.8 * ema
        samples++
        return if (samples < 3) null else (ema * (total - done) / 1000).toLong()
    }
}
