package com.blacksan.bmwdrive.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.blacksan.bmwdrive.uds.Dids
import com.blacksan.bmwdrive.uds.EcuAddress
import com.blacksan.bmwdrive.uds.ObdPid
import com.blacksan.bmwdrive.uds.UdsClient
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
 * Pure payload decoders. Each takes the DATA bytes of a 0x22 response (DID echo
 * already stripped) and returns null when the payload is too short.
 */
object DidDecoders {
    private fun u8(b: ByteArray, i: Int = 0): Int? = if (b.size > i) b[i].toInt() and 0xFF else null
    private fun u16(b: ByteArray, i: Int = 0): Int? =
        if (b.size > i + 1) ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF) else null

    /** rpm = u16 / 4 (OBD-II PID 0x0C scaling). */
    fun rpm(b: ByteArray): Int? = u16(b)?.let { it / 4 }

    /** km/h = u8. */
    fun speedKmh(b: ByteArray): Float? = u8(b)?.toFloat()

    /** Temperature in degC = u8 - 40 (OBD-II scaling). */
    fun tempC(b: ByteArray): Float? = u8(b)?.let { (it - 40).toFloat() }

    /** Manifold/boost pressure in kPa = u8 (OBD-II PID 0x0B scaling). */
    fun pressureKpa(b: ByteArray): Float? = u8(b)?.toFloat()

    /** Throttle percent = u8 * 100 / 255. */
    fun throttlePct(b: ByteArray): Float? = u8(b)?.let { it * 100f / 255f }

    /** Gear: 0 neutral, 1..8 forward, anything else (e.g. reverse/unknown) -> null. */
    fun gear(b: ByteArray): Int? = u8(b)?.takeIf { it in 0..8 }
}

/** Exponential low-pass: alpha 1.0 = no smoothing. */
fun lowPass(previous: Float?, sample: Float, alpha: Float): Float =
    if (previous == null) sample else previous + alpha * (sample - previous)

/** Convert m/s^2 to g. */
fun mpsToG(mps2: Float): Float = mps2 / 9.80665f

/** A fused device-frame acceleration sample in g. Longitudinal + = accelerating; lateral + = toward the right. */
data class ImuReading(val longG: Float, val latG: Float)

/** Supplies device IMU data; null when the sensor is missing. */
interface ImuProvider {
    val reading: StateFlow<ImuReading?>
    fun start()
    fun stop()
}

/**
 * How the phone is mounted, mapping device axes (x right, y up the screen)
 * to vehicle axes. Default assumes portrait with the top edge pointing forward.
 */
enum class ImuMount(val longAxis: Int, val longSign: Float, val latAxis: Int, val latSign: Float) {
    PORTRAIT_TOP_FORWARD(longAxis = 1, longSign = 1f, latAxis = 0, latSign = 1f),
    LANDSCAPE_LEFT_FORWARD(longAxis = 0, longSign = -1f, latAxis = 1, latSign = 1f),
}

/**
 * [ImuProvider] backed by TYPE_LINEAR_ACCELERATION (gravity removed). If the sensor
 * is absent, [reading] stays null and everything keeps working without g-forces.
 */
class SensorImu(
    context: Context,
    private val mount: ImuMount = ImuMount.PORTRAIT_TOP_FORWARD,
    private val alpha: Float = 0.25f,
) : ImuProvider, SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val _reading = MutableStateFlow<ImuReading?>(null)
    override val reading: StateFlow<ImuReading?> = _reading.asStateFlow()

    override fun start() {
        val s = sensor ?: return
        sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        _reading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val long = mpsToG(event.values[mount.longAxis]) * mount.longSign
        val lat = mpsToG(event.values[mount.latAxis]) * mount.latSign
        val prev = _reading.value
        _reading.value = ImuReading(lowPass(prev?.longG, long, alpha), lowPass(prev?.latG, lat, alpha))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Polls [client] against [dme] (OBD-II PIDs for rpm/speed/coolant, UNVERIFIED 0x22 DIDs for the rest) and fuses [imu] for g-forces.
 * A failed read keeps the previous value for that signal for a few cycles, then
 * clears it to null so the UI shows "—" rather than stale data.
 */
class UdsTelemetrySource(
    private val client: UdsClient,
    private val dme: EcuAddress = EcuAddress(0x0012, "DME", "Digital Motor Electronics"),
    private val imu: ImuProvider? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxMissedPolls: Int = 5,
) : TelemetrySource {
    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null

    /** [pid] = standard OBD-II mode 01 PID tried first (null = DID only); [did] is the UNVERIFIED 0x22 fallback. */
    private class Signal<T>(val did: Int, val decode: (ByteArray) -> T?, val pid: Int? = null) {
        var value: T? = null
        var missed = 0
    }

    private val rpm = Signal(Dids.RPM, DidDecoders::rpm, ObdPid.RPM)
    private val speed = Signal(Dids.SPEED, DidDecoders::speedKmh, ObdPid.SPEED)
    private val coolant = Signal(Dids.COOLANT, DidDecoders::tempC, ObdPid.COOLANT_TEMP)
    private val oil = Signal(Dids.OIL_TEMP, DidDecoders::tempC)
    private val intake = Signal(Dids.INTAKE_TEMP, DidDecoders::tempC)
    private val boost = Signal(Dids.BOOST, DidDecoders::pressureKpa)
    private val throttle = Signal(Dids.THROTTLE, DidDecoders::throttlePct)
    private val gear = Signal(Dids.GEAR, DidDecoders::gear)

    private suspend fun <T> poll(s: Signal<T>) {
        // Prefer the standard OBD-II PID (same scaling as the DID decoders); fall back to the DID read.
        val bytes = s.pid?.let { client.readObdPid(dme, it).getOrNull() }
            ?: client.readDataById(dme, s.did).getOrNull()
        val decoded = bytes?.let(s.decode)
        if (decoded != null) {
            s.value = decoded
            s.missed = 0
        } else if (++s.missed >= maxMissedPolls) {
            s.value = null
        }
    }

    /** Starts polling and returns immediately; calling it while running is a no-op. */
    override suspend fun start(hz: Int) {
        synchronized(lock) { startLocked(hz) }
    }

    private fun startLocked(hz: Int) {
        if (job?.isActive == true) return
        val periodMs = 1000L / hz.coerceIn(1, 20)
        imu?.start()
        job = scope.launch {
            while (isActive) {
                val t0 = clock()
                poll(rpm); poll(speed); poll(coolant); poll(oil)
                poll(intake); poll(boost); poll(throttle); poll(gear)
                val g = imu?.reading?.value
                _state.value = VehicleState(
                    rpm = rpm.value, speedKmh = speed.value, coolantC = coolant.value,
                    oilC = oil.value, intakeC = intake.value, boostKpa = boost.value,
                    throttlePct = throttle.value, gear = gear.value,
                    accelLong = g?.longG, accelLat = g?.latG, timestampMs = clock(),
                )
                delay((periodMs - (clock() - t0)).coerceAtLeast(1L))
            }
        }
    }

    override suspend fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        job?.cancel()
        job = null
        imu?.stop()
    }
}
