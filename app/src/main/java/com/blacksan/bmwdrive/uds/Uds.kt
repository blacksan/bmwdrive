package com.blacksan.bmwdrive.uds

/**
 * UDS (ISO 14229) service identifiers used by BMWDrive. Diagnostic requests are
 * carried to the vehicle inside DoIP diagnostic messages by the transport layer.
 */
object UdsService {
    const val DIAGNOSTIC_SESSION_CONTROL = 0x10
    const val ECU_RESET = 0x11
    const val READ_DTC_INFORMATION = 0x19   // sub 0x02 = reportDTCByStatusMask
    const val READ_DATA_BY_ID = 0x22
    const val SECURITY_ACCESS = 0x27
    const val CLEAR_DIAGNOSTIC_INFO = 0x14
    const val TESTER_PRESENT = 0x3E

    const val POSITIVE_RESPONSE_OFFSET = 0x40
    const val NEGATIVE_RESPONSE = 0x7F
    const val NRC_RESPONSE_PENDING = 0x78
}

/** Diagnostic session types for 0x10. */
enum class DiagSession(val id: Int) {
    DEFAULT(0x01),
    PROGRAMMING(0x02),
    EXTENDED(0x03),
}

/** A single stored diagnostic trouble code with its decoded description. */
data class FaultCode(
    val ecu: EcuAddress,
    /** 3-byte DTC, e.g. 0x20F220. */
    val code: Int,
    val statusMask: Int,
    val description: String,
) {
    /** BMW-style hex rendering, e.g. "20F220". */
    val hex: String get() = "%06X".format(code)
}

/** One addressable control unit. The full book is loaded from the EdiabasLib set. */
data class EcuAddress(
    /** DoIP logical address, e.g. 0x0012. */
    val logicalAddress: Int,
    /** Short name, e.g. "DME". */
    val shortName: String,
    /** Human label, e.g. "Digital Motor Electronics". */
    val label: String,
)

/**
 * High-level UDS client. Implementations wrap an [com.blacksan.bmwdrive.transport.EnetTransport]
 * and expose intent-level operations; they must guard destructive calls (clear faults)
 * behind explicit caller confirmation — the ViewModel/UI owns that prompt.
 */
interface UdsClient {

    /** Open a diagnostic session on the target ECU (defaults to EXTENDED). */
    suspend fun openSession(ecu: EcuAddress, session: DiagSession = DiagSession.EXTENDED): Result<Unit>

    /** 0x19 02 — read stored fault codes from one ECU, decoded via the fault-text table. */
    suspend fun readFaults(ecu: EcuAddress): Result<List<FaultCode>>

    /** Scan the whole address book and aggregate faults. Read-only. */
    suspend fun scanAll(book: List<EcuAddress>): Result<List<FaultCode>>

    /**
     * 0x14 — clear fault memory. DESTRUCTIVE: clearing across modules can restart
     * control units and reboot the head unit. Callers MUST confirm first.
     */
    suspend fun clearFaults(ecu: EcuAddress, functional: Boolean = false): Result<Unit>

    /** 0x22 — read one data identifier (raw bytes); telemetry maps these to signals. */
    suspend fun readDataById(ecu: EcuAddress, did: Int): Result<ByteArray>

    /**
     * OBD-II mode 01 read of [pid] (see [ObdPid]); returns the data bytes after `41 <pid>`.
     * UNVERIFIED whether a G-series DME answers mode 01 over DoIP.
     */
    suspend fun readObdPid(ecu: EcuAddress, pid: Int): Result<ByteArray>
}
