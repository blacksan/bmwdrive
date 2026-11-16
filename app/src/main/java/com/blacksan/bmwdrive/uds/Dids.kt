package com.blacksan.bmwdrive.uds

/**
 * Data identifiers for 0x22 reads. There is NO verified B48 DID list.
 * Every value here is a PLACEHOLDER/CANDIDATE -- confirm each on the car before trusting it.
 * Note: SOME/IP high-rate telemetry is NOT reachable over the OBD/ENET gateway.
 */
object Dids {
    // ISO 14229 standard identifiers.
    const val VIN = 0xF190              // UNVERIFIED — confirm on car
    const val ECU_SW_VERSION = 0xF189   // UNVERIFIED — confirm on car
    const val ACTIVE_SESSION = 0xF186   // UNVERIFIED — confirm on car

    // Telemetry placeholders (low byte mirrors the OBD-II mode 01 PID number).
    const val RPM = 0xF40C              // UNVERIFIED — confirm on car
    const val SPEED = 0xF40D            // UNVERIFIED — confirm on car
    const val COOLANT = 0xF405          // UNVERIFIED — confirm on car
    const val OIL_TEMP = 0xF45C         // UNVERIFIED — confirm on car
    const val INTAKE_TEMP = 0xF40F      // UNVERIFIED — confirm on car
    const val BOOST = 0xF40B            // UNVERIFIED — confirm on car (manifold pressure)
    const val THROTTLE = 0xF411         // UNVERIFIED — confirm on car
    const val GEAR = 0xF4A4             // UNVERIFIED — confirm on car
}
