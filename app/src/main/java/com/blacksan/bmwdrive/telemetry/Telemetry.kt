package com.blacksan.bmwdrive.telemetry

import kotlinx.coroutines.flow.StateFlow

/**
 * Live vehicle state sampled from the car. Fields are nullable because not every
 * signal is available on every DME software version or at every poll — the UI
 * renders "—" for nulls rather than guessing.
 *
 * Over the ENET/DoIP link these come from periodic UDS 0x22 reads (and, where
 * available, standard OBD-II mode 01 PIDs). High-rate SOME/IP telemetry is NOT
 * reachable from the OBD gateway, so sample rates are modest (see [TelemetrySource]).
 */
data class VehicleState(
    val rpm: Int? = null,
    val speedKmh: Float? = null,
    val coolantC: Float? = null,
    val oilC: Float? = null,
    val intakeC: Float? = null,
    val boostKpa: Float? = null,
    val throttlePct: Float? = null,
    val gear: Int? = null,
    /** Longitudinal / lateral g from the device IMU, fused in by the telemetry layer. */
    val accelLong: Float? = null,
    val accelLat: Float? = null,
    val timestampMs: Long = 0L,
) {
    companion object { val EMPTY = VehicleState() }
}

/**
 * Supplies [VehicleState] updates. A real implementation polls UDS DIDs/PIDs over
 * the transport and fuses device sensor data; the demo implementation replays a
 * synthetic drive so every screen works with no car attached.
 */
interface TelemetrySource {
    val state: StateFlow<VehicleState>

    /** Begin sampling at approximately [hz] updates per second (best-effort). */
    suspend fun start(hz: Int = 10)

    /** Stop sampling. */
    suspend fun stop()
}
