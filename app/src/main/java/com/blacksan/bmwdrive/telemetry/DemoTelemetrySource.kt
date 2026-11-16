package com.blacksan.bmwdrive.telemetry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure synthetic-drive model. One 90 s loop: idle, a full-throttle pull to ~200 km/h,
 * hard braking, a slalom section with lateral g, then idle again. Temperatures follow
 * [warmupSec], the cumulative simulated time since the first sample.
 */
object DemoDrive {
    const val LOOP_SEC = 90.0
    const val REDLINE_RPM = 6600.0

    /** Top speed (km/h) of each gear at [REDLINE_RPM]; index 0 = 1st gear. */
    private val gearTopKmh = doubleArrayOf(58.0, 95.0, 138.0, 182.0, 230.0, 290.0)

    /** Speed in km/h at loop time [t] (seconds). */
    fun speedKmh(t: Double): Double {
        val x = ((t % LOOP_SEC) + LOOP_SEC) % LOOP_SEC
        return when {
            x < 3.0 -> 0.0
            x < 27.0 -> 225.0 * (1 - exp(-(x - 3.0) / 9.0))
            x < 45.0 -> {
                val vEnd = 225.0 * (1 - exp(-24.0 / 9.0))
                vEnd * (0.5 + 0.5 * cos(PI * (x - 27.0) / 18.0))
            }
            x < 75.0 -> 70.0 + 18.0 * sin(2 * PI * (x - 45.0) / 10.0 + 0.6)
            x < 80.0 -> 70.0 * (0.5 + 0.5 * cos(PI * (x - 75.0) / 5.0))
            else -> 0.0
        }
    }

    /** Gear (1..6) appropriate for [kmh]; shifts when rpm would exceed redline. */
    fun gearFor(kmh: Double): Int {
        if (kmh < 1.0) return 1
        val idx = gearTopKmh.indexOfFirst { kmh <= it * 0.985 }
        return (if (idx < 0) gearTopKmh.size else idx + 1)
    }

    /** Engine rpm for [kmh] in [gear]; idles at 800 rpm. */
    fun rpmFor(kmh: Double, gear: Int): Double =
        (REDLINE_RPM * kmh / gearTopKmh[gear - 1]).coerceAtLeast(800.0)

    /** Samples the drive at loop time [t]; [warmupSec] drives the temperature curves. */
    fun sample(t: Double, warmupSec: Double, timestampMs: Long): VehicleState {
        val dt = 0.1
        val v = speedKmh(t)
        val vPrev = speedKmh(t - dt)
        val accelG = (v - vPrev) / 3.6 / dt / 9.81
        val gear = gearFor(v)
        val rpm = rpmFor(v, gear)
        val x = ((t % LOOP_SEC) + LOOP_SEC) % LOOP_SEC
        val throttle = when {
            x in 3.0..27.0 -> 100.0
            x in 45.0..75.0 -> (35.0 + 25.0 * accelG.coerceIn(-0.5, 0.5) * 2).coerceIn(10.0, 70.0)
            else -> 0.0
        }
        val slalom = if (x in 45.0..75.0) 1.1 * sin(2 * PI * (x - 45.0) / 6.0) else 0.0
        val gaugeBoost = (throttle / 100.0 * 140.0 * min(1.0, (rpm - 1500) / 2500.0)).coerceAtLeast(0.0)
        // boostKpa is ABSOLUTE manifold pressure (as a real OBD read returns); a boost gauge should display value - 100 for gauge pressure.
        val boost = 30.0 + gaugeBoost * 110.0 / 140.0
        val coolant = 30.0 + 62.0 * (1 - exp(-warmupSec / 150.0))
        val oil = 22.0 + 90.0 * (1 - exp(-warmupSec / 300.0))
        return VehicleState(
            rpm = rpm.toInt(),
            speedKmh = v.toFloat(),
            coolantC = coolant.toFloat(),
            oilC = oil.toFloat(),
            intakeC = (28.0 + gaugeBoost * 0.08).toFloat(),
            boostKpa = boost.toFloat(),
            throttlePct = throttle.toFloat(),
            gear = if (v < 1.0) 1 else gear,
            accelLong = accelG.toFloat(),
            accelLat = slalom.toFloat(),
            timestampMs = timestampMs,
        )
    }
}

/** Replays [DemoDrive] so every screen works with no car attached. */
class DemoTelemetrySource(
    private val clock: () -> Long = System::currentTimeMillis,
) : TelemetrySource {
    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val lock = Any()
    private var simSec = 0.0

    /** Starts the replay loop and returns immediately; calling it while running is a no-op. */
    override suspend fun start(hz: Int) {
        synchronized(lock) { startLocked(hz) }
    }

    private fun startLocked(hz: Int) {
        if (job?.isActive == true) return
        val periodMs = (1000L / hz.coerceIn(1, 60))
        job = scope.launch {
            var last = clock()
            while (isActive) {
                val now = clock()
                simSec += (now - last) / 1000.0
                last = now
                _state.value = DemoDrive.sample(simSec, simSec, now)
                delay(periodMs)
            }
        }
    }

    override suspend fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        job?.cancel()
        job = null
    }
}
