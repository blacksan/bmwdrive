package com.blacksan.bmwdrive.uds

import com.blacksan.bmwdrive.transport.EnetTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** ECU answered 0x7F <sid> <nrc>. */
class UdsNegativeResponseException(val service: Int, val nrc: Int) :
    Exception("UDS negative response to 0x%02X: NRC 0x%02X".format(service, nrc))

/** Operation deliberately not implemented (e.g. BMW SecurityAccess key algorithm). */
class UdsNotSupportedException(message: String) : Exception(message)

/** Malformed / unexpected UDS payload. */
class UdsProtocolException(message: String) : Exception(message)

/**
 * [UdsClient] over an injected [EnetTransport].
 *
 * Session notes (community-general, UNVERIFIED on a G20): 0x19 and 0x22 reads usually work in
 * the default session; 0x14 clear and writes need the extended session (0x10 0x03); protected
 * operations need 0x27 SecurityAccess.
 *
 * [clearFaults] is DESTRUCTIVE. The [UdsClient] interface has no confirmation parameter, so
 * the UI/ViewModel MUST obtain explicit user confirmation before calling it.
 */
class RealUdsClient(
    private val transport: EnetTransport,
    private val addressBook: List<EcuAddress> = EcuAddressBook.all,
) : UdsClient {

    private val sessions = ConcurrentHashMap<Int, DiagSession>()

    /** Send [payload] to [ecu] and return the positive response, mapping 0x7F to an exception. */
    private suspend fun request(ecu: EcuAddress, payload: ByteArray): Result<ByteArray> {
        val sid = payload[0].toInt() and 0xFF
        val resp = transport.sendUds(payload, ecu.logicalAddress).getOrElse { return Result.failure(it) }
        if (resp.isEmpty()) return Result.failure(UdsProtocolException("Empty UDS response"))
        val first = resp[0].toInt() and 0xFF
        if (first == UdsService.NEGATIVE_RESPONSE) {
            val nrc = if (resp.size >= 3) resp[2].toInt() and 0xFF else -1
            return Result.failure(UdsNegativeResponseException(sid, nrc))
        }
        if (first != sid + UdsService.POSITIVE_RESPONSE_OFFSET) {
            return Result.failure(UdsProtocolException("Unexpected response SID 0x%02X for request 0x%02X".format(first, sid)))
        }
        return Result.success(resp)
    }

    override suspend fun openSession(ecu: EcuAddress, session: DiagSession): Result<Unit> =
        request(ecu, byteArrayOf(UdsService.DIAGNOSTIC_SESSION_CONTROL.toByte(), session.id.toByte())).map {
            sessions[ecu.logicalAddress] = session
        }

    override suspend fun readFaults(ecu: EcuAddress): Result<List<FaultCode>> {
        // 0x19 0x02 <statusMask>: reportDTCByStatusMask. Mask 0xFF = any status bit.
        val req = byteArrayOf(UdsService.READ_DTC_INFORMATION.toByte(), 0x02, 0xFF.toByte())
        return request(ecu, req).mapCatching { parseDtcResponse(ecu, it) }
    }

    override suspend fun scanAll(book: List<EcuAddress>): Result<List<FaultCode>> {
        val targets = book.ifEmpty { addressBook }
        val all = mutableListOf<FaultCode>()
        var firstError: Throwable? = null
        var anyOk = false
        for (ecu in targets) {
            val r = readFaults(ecu)
            r.onSuccess { anyOk = true; all += it }
            r.onFailure { if (firstError == null) firstError = it } // absent ECUs simply do not answer
        }
        return if (anyOk || firstError == null) Result.success(all) else Result.failure(firstError!!)
    }

    /**
     * 0x14 clear DTCs (group 0xFFFFFF = all). DESTRUCTIVE -- caller must have confirmed.
     * Opens the extended session first if this client has not already done so for [ecu].
     * [functional] = true is routed via the ZGW (0x0010) instead of the module itself --
     * UNVERIFIED that the ZGW fans this out on a G-series.
     */
    override suspend fun clearFaults(ecu: EcuAddress, functional: Boolean): Result<Unit> {
        val target = if (functional) EcuAddressBook.ZGW else ecu
        if (sessions[target.logicalAddress] != DiagSession.EXTENDED) {
            openSession(target, DiagSession.EXTENDED).getOrElse { return Result.failure(it) }
        }
        val req = byteArrayOf(UdsService.CLEAR_DIAGNOSTIC_INFO.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        return request(target, req).map { }
    }

    /** 0x22: returns the data bytes AFTER the echoed 2-byte DID. */
    override suspend fun readDataById(ecu: EcuAddress, did: Int): Result<ByteArray> {
        val hi = (did shr 8).toByte()
        val lo = did.toByte()
        return request(ecu, byteArrayOf(UdsService.READ_DATA_BY_ID.toByte(), hi, lo)).mapCatching { r ->
            if (r.size < 3 || r[1] != hi || r[2] != lo) throw UdsProtocolException("DID echo mismatch")
            r.copyOfRange(3, r.size)
        }
    }

    /**
     * OBD-II mode 01 read (e.g. [ObdPid.RPM]); returns data bytes after `41 <pid>`.
     * Sent physically to [ecu] (default DME). Functional addressing (CAN 0x7DF) has no
     * confirmed DoIP equivalent on BMW -- UNVERIFIED whether the DME answers mode 01 over DoIP.
     */
    suspend fun readObdPid(pid: Int, ecu: EcuAddress = EcuAddressBook.DME): Result<ByteArray> =
        request(ecu, byteArrayOf(0x01, pid.toByte())).mapCatching { r ->
            if (r.size < 2 || (r[1].toInt() and 0xFF) != pid) throw UdsProtocolException("PID echo mismatch")
            r.copyOfRange(2, r.size)
        }

    /** 0x27 <odd level>: request a security seed. Key computation is NOT implemented. */
    suspend fun requestSecuritySeed(ecu: EcuAddress, level: Int = 0x01): Result<ByteArray> =
        request(ecu, byteArrayOf(UdsService.SECURITY_ACCESS.toByte(), level.toByte())).mapCatching { it.copyOfRange(2, it.size) }

    /**
     * TODO(security): BMW seed->key algorithm is proprietary and deliberately not implemented.
     * Always returns NotSupported.
     */
    @Suppress("UNUSED_PARAMETER")
    fun computeSecurityKey(seed: ByteArray, level: Int): Result<ByteArray> =
        Result.failure(UdsNotSupportedException("SecurityAccess key computation is not implemented"))

    /**
     * Start a TesterPresent (0x3E 0x00) loop to hold a non-default session. Cancel the
     * returned Job to stop. Failures are swallowed (the next tick retries).
     */
    fun startKeepAlive(scope: CoroutineScope, ecu: EcuAddress, intervalMs: Long = 2_000): Job =
        scope.launch {
            while (isActive) {
                request(ecu, byteArrayOf(UdsService.TESTER_PRESENT.toByte(), 0x00))
                delay(intervalMs)
            }
        }

    companion object {
        /**
         * Response layout: [0]=0x59, [1]=0x02, [2]=DTCStatusAvailabilityMask, then repeated
         * 4-byte records: DTC high, DTC mid, DTC low, statusOfDTC.
         */
        fun parseDtcResponse(ecu: EcuAddress, resp: ByteArray): List<FaultCode> {
            if (resp.size < 3 || resp[0].toInt() != 0x59) throw UdsProtocolException("Not a 0x19 response")
            val out = mutableListOf<FaultCode>()
            var i = 3
            while (i + 4 <= resp.size) {
                val code = ((resp[i].toInt() and 0xFF) shl 16) or
                    ((resp[i + 1].toInt() and 0xFF) shl 8) or
                    (resp[i + 2].toInt() and 0xFF)
                val status = resp[i + 3].toInt() and 0xFF
                out += FaultCode(ecu, code, status, FaultText.describe(code))
                i += 4
            }
            return out
        }
    }
}

/** Standard OBD-II mode 01 PIDs (portable across ECUs; SAE J1979). */
object ObdPid {
    const val COOLANT_TEMP = 0x05 // A - 40 degC
    const val RPM = 0x0C          // (256*A + B) / 4
    const val SPEED = 0x0D        // A km/h
}

/**
 * CANDIDATE data identifiers for 0x22 reads. There is NO verified B48 DID list.
 * None of these are authoritative; confirm each on the car before trusting it.
 * Note: SOME/IP high-rate telemetry is NOT reachable over the OBD/ENET gateway.
 */
object CandidateDids {
    const val VIN = 0xF190              // UNVERIFIED -- confirm on car (ISO 14229 standard VIN DID)
    const val ECU_SW_VERSION = 0xF189   // UNVERIFIED -- confirm on car (ISO 14229 standard)
    const val ACTIVE_SESSION = 0xF186   // UNVERIFIED -- confirm on car (ISO 14229 standard)
}
