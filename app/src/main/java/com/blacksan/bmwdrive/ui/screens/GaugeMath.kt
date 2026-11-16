package com.blacksan.bmwdrive.ui.screens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Pure gauge/dashboard math, kept free of Compose so it is unit-testable. */

/** Maps [value] in [min]..[max] to 0..1, clamped. */
fun fractionOf(value: Float, min: Float, max: Float): Float =
    if (max <= min) 0f else ((value - min) / (max - min)).coerceIn(0f, 1f)

/** Format a nullable number or "—" when null. */
fun fmt(value: Float?, decimals: Int = 0, suffix: String = ""): String =
    if (value == null) "—" else "%.${decimals}f".format(value) + suffix

/** Format a nullable int or "—". */
fun fmt(value: Int?, suffix: String = ""): String = if (value == null) "—" else "$value$suffix"

// ---------------------------------------------------------------- Redline

enum class LedLevel { OFF, GREEN, AMBER, RED }

/**
 * State of each of [count] shift LEDs. LEDs light progressively from [startRpm] to
 * [redlineRpm]; the first 60% are green, the next ~20% amber, the rest red.
 */
fun shiftLedStates(rpm: Int?, startRpm: Int, redlineRpm: Int, count: Int = 12): List<LedLevel> {
    val lit = if (rpm == null) 0 else
        (fractionOf(rpm.toFloat(), startRpm.toFloat(), redlineRpm.toFloat()) * count + 0.0001f).toInt()
    return List(count) { i ->
        when {
            i >= lit -> LedLevel.OFF
            i < (count * 0.6f).toInt() -> LedLevel.GREEN
            i < (count * 0.8f).toInt() -> LedLevel.AMBER
            else -> LedLevel.RED
        }
    }
}

/** True once rpm reaches the shift point. */
fun shouldUpshift(rpm: Int?, shiftRpm: Int): Boolean = rpm != null && rpm >= shiftRpm

// ---------------------------------------------------------------- Warm-up

/**
 * Safe rev limit rising with oil temperature: [coldLimit] up to 40 degC, then linearly
 * to [redline] at [warmOilC].
 */
fun safeRevLimit(oilC: Float?, redline: Int = 6500, coldLimit: Int = 3500, warmOilC: Float = 90f): Int? {
    if (oilC == null) return null
    val f = fractionOf(oilC, 40f, warmOilC)
    return (coldLimit + (redline - coldLimit) * f).toInt()
}

/** Warm-up progress 0..100: oil temp weighted 60%, coolant 40%, from 20 degC to operating temp. */
fun warmUpPercent(oilC: Float?, coolantC: Float?, oilTarget: Float = 90f, coolantTarget: Float = 88f): Float? {
    if (oilC == null && coolantC == null) return null
    val o = oilC?.let { fractionOf(it, 20f, oilTarget) }
    val c = coolantC?.let { fractionOf(it, 20f, coolantTarget) }
    val f = when {
        o != null && c != null -> o * 0.6f + c * 0.4f
        else -> o ?: c!!
    }
    return f * 100f
}

// ---------------------------------------------------------------- G-force

/** One lateral/longitudinal sample in g. */
data class GPoint(val lat: Float, val long: Float, val timeMs: Long)

/** Resultant g magnitude. */
fun gMagnitude(lat: Float, long: Float): Float = sqrt(lat * lat + long * long)

/** Appends [p] and keeps only the newest [max] points. */
fun List<GPoint>.pushed(p: GPoint, max: Int = 80): List<GPoint> = (this + p).takeLast(max)

/** Largest-magnitude point in the trail, or null. */
fun List<GPoint>.peak(): GPoint? = maxByOrNull { gMagnitude(it.lat, it.long) }

/** Fade alpha for trail point at [index] of [size] (oldest ~0.05, newest 1). */
fun trailAlpha(index: Int, size: Int): Float =
    if (size <= 1) 1f else 0.05f + 0.95f * index / (size - 1)

// ---------------------------------------------------------------- Drag

enum class DragPhase { WAIT_STOP, ARMED, RUNNING, FINISHED }
enum class DragMark(val label: String) {
    ZERO_50("0-50 km/h"), ZERO_100("0-100 km/h"), H100_200("100-200 km/h"), QUARTER("1/4 mile")
}

const val QUARTER_MILE_M = 402.336
private const val LAUNCH_KMH = 2f

/**
 * Immutable drag-timer state machine driven by [step]. Splits are in milliseconds from
 * launch (100-200 is from the 100 km/h crossing). Crossing times are interpolated
 * between samples.
 */
data class DragRun(
    val phase: DragPhase = DragPhase.WAIT_STOP,
    val launchMs: Double? = null,
    val lastMs: Long? = null,
    val lastSpeed: Float? = null,
    val distanceM: Double = 0.0,
    val t100Ms: Double? = null,
    val splits: Map<DragMark, Long> = emptyMap(),
) {
    /** Advance with a new sample. Null speed leaves the state unchanged. */
    fun step(speedKmh: Float?, nowMs: Long): DragRun {
        if (speedKmh == null) return this
        if (lastMs != null && nowMs <= lastMs) return this
        val base = copy(lastMs = nowMs, lastSpeed = speedKmh)
        return when (phase) {
            DragPhase.WAIT_STOP -> if (speedKmh < LAUNCH_KMH) base.copy(phase = DragPhase.ARMED) else base
            DragPhase.ARMED ->
                if (speedKmh >= LAUNCH_KMH) base.copy(phase = DragPhase.RUNNING, launchMs = (lastMs ?: nowMs).toDouble())
                    .advance(lastSpeed ?: 0f, lastMs ?: nowMs, speedKmh, nowMs)
                else base
            DragPhase.RUNNING -> advance(lastSpeed ?: 0f, lastMs ?: nowMs, speedKmh, nowMs).let {
                if (speedKmh < LAUNCH_KMH || (it.splits.containsKey(DragMark.QUARTER) && it.splits.containsKey(DragMark.H100_200)))
                    it.copy(phase = DragPhase.FINISHED) else it
            }
            DragPhase.FINISHED -> this
        }
    }

    private fun advance(v0: Float, t0: Long, v1: Float, t1: Long): DragRun {
        val launch = launchMs ?: return copy(lastMs = t1, lastSpeed = v1)
        val dtS = (t1 - t0) / 1000.0
        val dist = distanceM + (v0 + v1) / 2.0 / 3.6 * dtS
        val s = splits.toMutableMap()
        fun cross(target: Float): Double? =
            if (v0 < target && v1 >= target) t0 + (t1 - t0) * ((target - v0) / (v1 - v0)).toDouble() else null
        cross(50f)?.let { s.putIfAbsent(DragMark.ZERO_50, (it - launch).toLong()) }
        cross(100f)?.let { s.putIfAbsent(DragMark.ZERO_100, (it - launch).toLong()) }
        var t100 = t100Ms
        cross(100f)?.let { if (t100 == null) t100 = it }
        cross(200f)?.let { c -> t100?.let { s.putIfAbsent(DragMark.H100_200, (c - it).toLong()) } }
        if (distanceM < QUARTER_MILE_M && dist >= QUARTER_MILE_M) {
            val f = (QUARTER_MILE_M - distanceM) / (dist - distanceM)
            s.putIfAbsent(DragMark.QUARTER, ((t0 + (t1 - t0) * f) - launch).toLong())
        }
        return copy(distanceM = dist, t100Ms = t100, splits = s, lastMs = t1, lastSpeed = v1)
    }

    /** Milliseconds since launch while running, else null. */
    fun elapsedMs(): Long? = if (phase == DragPhase.RUNNING && launchMs != null && lastMs != null)
        (lastMs - launchMs).toLong() else null
}

/** Formats milliseconds as seconds with 2 decimals, or "—". */
fun fmtSeconds(ms: Long?): String = if (ms == null) "—" else "%.2f s".format(ms / 1000.0)

// ---------------------------------------------------------------- Dyno

/** One binned dyno estimate. */
data class DynoPoint(val rpm: Int, val kw: Float, val nm: Float)

/** Tunable inputs for the dyno estimate. */
data class DynoConfig(
    val massKg: Float = 1650f,
    val cdA: Float = 0.65f,
    val rollingCoeff: Float = 0.012f,
    val drivetrainLoss: Float = 0.15f,
)

private const val G = 9.80665f
private const val RHO = 1.2f

/** Estimated crank power in kW from speed (km/h) and longitudinal accel (g); null if not meaningful. */
fun estimateCrankKw(speedKmh: Float, accelG: Float, cfg: DynoConfig): Float? {
    if (speedKmh < 10f || accelG <= 0.05f) return null
    val v = speedKmh / 3.6f
    val force = cfg.massKg * accelG * G + 0.5f * RHO * cfg.cdA * v * v + cfg.rollingCoeff * cfg.massKg * G
    val wheelKw = force * v / 1000f
    return wheelKw / (1f - cfg.drivetrainLoss)
}

/** Torque in Nm from power in kW at [rpm]. */
fun torqueNm(kw: Float, rpm: Int): Float = if (rpm <= 0) 0f else kw * 1000f / (rpm * 2f * Math.PI.toFloat() / 60f)

/**
 * Folds one sample into rpm bins ([binRpm] wide) with an exponential average.
 * Only accepts near-full-throttle pulls above 1500 rpm.
 */
fun Map<Int, DynoPoint>.withSample(
    rpm: Int?, speedKmh: Float?, accelG: Float?, throttlePct: Float?,
    cfg: DynoConfig, binRpm: Int = 250,
): Map<Int, DynoPoint> {
    if (rpm == null || speedKmh == null || accelG == null || rpm < 1500) return this
    if ((throttlePct ?: 100f) < 85f) return this
    val kw = estimateCrankKw(speedKmh, accelG, cfg) ?: return this
    val bin = (rpm / binRpm) * binRpm + binRpm / 2
    val old = this[bin]
    val newKw = if (old == null) kw else old.kw * 0.7f + kw * 0.3f
    return this + (bin to DynoPoint(bin, newKw, torqueNm(newKw, bin)))
}

/** Peak values for readouts. */
fun Collection<DynoPoint>.peakKw(): DynoPoint? = maxByOrNull { it.kw }
fun Collection<DynoPoint>.peakNm(): DynoPoint? = maxByOrNull { it.nm }

/** Largest absolute value, floored at [floor], for axis scaling. */
fun axisMax(values: List<Float>, floor: Float): Float = max(floor, values.maxOfOrNull { abs(it) } ?: floor)
