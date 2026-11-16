package com.blacksan.bmwdrive.transport

/**
 * DoIP (ISO 13400) constants and pure frame helpers. No I/O, no Android -- unit-testable.
 *
 * Generic DoIP header (8 bytes, all multi-byte fields big-endian). Offsets are from the
 * start of the frame:
 *
 *   [0]      protocol version          0x02
 *   [1]      inverse protocol version  0xFD
 *   [2..3]   payload type
 *   [4..7]   payload length (bytes following the header)
 *   [8..]    payload
 *
 * Payload offsets in the parse functions below are relative to the payload start; the
 * KDoc also gives the absolute frame offset (payload offset + 8).
 */
object Doip {
    const val PORT = 13400
    const val PROTOCOL_VERSION = 0x02
    const val PROTOCOL_VERSION_INVERSE = 0xFD
    const val HEADER_SIZE = 8

    /** Sanity cap on a received payload so a corrupt length cannot allocate gigabytes. */
    const val MAX_PAYLOAD = 0x10000

    /** Payload types. */
    object Type {
        const val GENERIC_NACK = 0x0000          // TCP/UDP: header NACK
        const val VEHICLE_ID_REQUEST = 0x0001    // UDP, empty payload
        const val VEHICLE_ID_REQUEST_BY_VIN = 0x0003 // UDP
        const val VEHICLE_ANNOUNCEMENT = 0x0004  // UDP, also the reply to 0x0001
        const val ROUTING_ACTIVATION_REQUEST = 0x0005  // TCP
        const val ROUTING_ACTIVATION_RESPONSE = 0x0006 // TCP
        const val ALIVE_CHECK_REQUEST = 0x0007   // TCP (standard ISO 13400)
        const val ALIVE_CHECK_RESPONSE = 0x0008  // TCP (standard ISO 13400)
        const val DIAGNOSTIC_MESSAGE = 0x8001    // TCP
        const val DIAGNOSTIC_ACK = 0x8002        // TCP: queued, NOT a UDS result
        const val DIAGNOSTIC_NACK = 0x8003       // TCP
    }

    /** Routing activation response codes (payload byte [4], frame byte [12]). */
    object ActivationCode {
        const val UNKNOWN_SOURCE = 0x00
        const val NO_FREE_SOCKET = 0x01
        const val WRONG_SOURCE_ADDRESS = 0x02
        const val ALREADY_REGISTERED = 0x03
        const val MISSING_AUTHENTICATION = 0x04
        const val REJECTED_CONFIRMATION = 0x05
        const val UNSUPPORTED_TYPE = 0x06
        const val SUCCESS = 0x10                  // verified for BMW
        const val SUCCESS_PENDING_CONFIRMATION = 0x11

        fun describe(code: Int): String = when (code) {
            UNKNOWN_SOURCE -> "unknown source address"
            NO_FREE_SOCKET -> "gateway has no free socket (another tester connected?)"
            WRONG_SOURCE_ADDRESS -> "source address does not match the registered one"
            ALREADY_REGISTERED -> "source address already active on another socket"
            MISSING_AUTHENTICATION -> "authentication required"
            REJECTED_CONFIRMATION -> "confirmation rejected"
            UNSUPPORTED_TYPE -> "unsupported activation type"
            SUCCESS -> "success"
            SUCCESS_PENDING_CONFIRMATION -> "success, pending confirmation"
            else -> "unknown response code 0x%02X".format(code)
        }
    }

    const val ACTIVATION_TYPE_DEFAULT = 0x00

    private val EMPTY = ByteArray(0)

    // ---------------------------------------------------------------- build

    /** Build a complete frame: 8-byte header followed by [payload]. */
    fun buildFrame(payloadType: Int, payload: ByteArray = EMPTY): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = PROTOCOL_VERSION.toByte()
        out[1] = PROTOCOL_VERSION_INVERSE.toByte()
        out[2] = (payloadType shr 8).toByte()
        out[3] = payloadType.toByte()
        out[4] = (payload.size ushr 24).toByte()
        out[5] = (payload.size ushr 16).toByte()
        out[6] = (payload.size ushr 8).toByte()
        out[7] = payload.size.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** 0x0001 vehicle identification request: header only, payload length 0 (frame = 8 bytes). */
    fun buildVehicleIdRequest(): ByteArray = buildFrame(Type.VEHICLE_ID_REQUEST)

    /**
     * 0x0005 routing activation request. Payload (7 bytes):
     *   payload[0..1] (frame 8..9)   source address (tester, 0x0E80)
     *   payload[2]    (frame 10)     activation type (0x00 default)
     *   payload[3..6] (frame 11..14) reserved by ISO = 0
     * Total frame length 15. (An optional 4-byte OEM field would extend this to 19.)
     */
    fun buildRoutingActivationRequest(
        sourceAddress: Int,
        activationType: Int = ACTIVATION_TYPE_DEFAULT,
    ): ByteArray {
        val p = ByteArray(7)
        p[0] = (sourceAddress shr 8).toByte()
        p[1] = sourceAddress.toByte()
        p[2] = activationType.toByte()
        // p[3..6] reserved = 0
        return buildFrame(Type.ROUTING_ACTIVATION_REQUEST, p)
    }

    /**
     * 0x8001 diagnostic message. Payload:
     *   payload[0..1] (frame 8..9)   source address
     *   payload[2..3] (frame 10..11) target address
     *   payload[4..]  (frame 12..)   UDS user data
     */
    fun buildDiagnosticMessage(sourceAddress: Int, targetAddress: Int, uds: ByteArray): ByteArray {
        val p = ByteArray(4 + uds.size)
        p[0] = (sourceAddress shr 8).toByte()
        p[1] = sourceAddress.toByte()
        p[2] = (targetAddress shr 8).toByte()
        p[3] = targetAddress.toByte()
        uds.copyInto(p, 4)
        return buildFrame(Type.DIAGNOSTIC_MESSAGE, p)
    }

    /** 0x0008 alive check response: payload[0..1] (frame 8..9) = source address. */
    fun buildAliveCheckResponse(sourceAddress: Int): ByteArray =
        buildFrame(
            Type.ALIVE_CHECK_RESPONSE,
            byteArrayOf((sourceAddress shr 8).toByte(), sourceAddress.toByte()),
        )

    // ---------------------------------------------------------------- parse

    data class Header(val version: Int, val payloadType: Int, val payloadLength: Int)
    data class Frame(val payloadType: Int, val payload: ByteArray)

    /** Parse the 8-byte header at [offset]; fails on short input, bad version/inverse, or absurd length. */
    fun parseHeader(buf: ByteArray, offset: Int = 0): Result<Header> {
        if (buf.size - offset < HEADER_SIZE) {
            return Result.failure(IllegalArgumentException("DoIP header needs 8 bytes, got ${buf.size - offset}"))
        }
        val version = buf[offset].toInt() and 0xFF
        val inverse = buf[offset + 1].toInt() and 0xFF
        if (version != PROTOCOL_VERSION || inverse != PROTOCOL_VERSION_INVERSE) {
            return Result.failure(
                IllegalArgumentException("Bad DoIP version bytes 0x%02X 0x%02X".format(version, inverse)),
            )
        }
        val type = u16(buf, offset + 2)
        val len = ((buf[offset + 4].toInt() and 0xFF) shl 24) or
            ((buf[offset + 5].toInt() and 0xFF) shl 16) or
            ((buf[offset + 6].toInt() and 0xFF) shl 8) or
            (buf[offset + 7].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD) {
            return Result.failure(IllegalArgumentException("DoIP payload length $len out of range"))
        }
        return Result.success(Header(version, type, len))
    }

    /** Parse one complete frame (header + payload) from [buf]; extra trailing bytes are ignored. */
    fun parseFrame(buf: ByteArray): Result<Frame> {
        val h = parseHeader(buf).getOrElse { return Result.failure(it) }
        if (buf.size < HEADER_SIZE + h.payloadLength) {
            return Result.failure(IllegalArgumentException("DoIP frame truncated"))
        }
        return Result.success(Frame(h.payloadType, buf.copyOfRange(HEADER_SIZE, HEADER_SIZE + h.payloadLength)))
    }

    /**
     * 0x0006 routing activation response. Payload (min 9 bytes):
     *   payload[0..1] (frame 8..9)   logical address of the tester
     *   payload[2..3] (frame 10..11) logical address of the DoIP entity (gateway)
     *   payload[4]    (frame 12)     response code (0x10 = success)
     *   payload[5..8] (frame 13..16) reserved
     */
    data class RoutingActivationResponse(val testerAddress: Int, val entityAddress: Int, val code: Int) {
        val isSuccess: Boolean get() = code == ActivationCode.SUCCESS
    }

    fun parseRoutingActivationResponse(payload: ByteArray): Result<RoutingActivationResponse> {
        if (payload.size < 9) {
            return Result.failure(IllegalArgumentException("Routing activation response too short: ${payload.size}"))
        }
        return Result.success(
            RoutingActivationResponse(u16(payload, 0), u16(payload, 2), payload[4].toInt() and 0xFF),
        )
    }

    /** 0x8001 payload: source = payload[0..1], target = payload[2..3], UDS = payload[4..]. */
    data class DiagnosticMessage(val sourceAddress: Int, val targetAddress: Int, val userData: ByteArray)

    fun parseDiagnosticMessage(payload: ByteArray): Result<DiagnosticMessage> {
        if (payload.size < 4) {
            return Result.failure(IllegalArgumentException("Diagnostic message too short: ${payload.size}"))
        }
        return Result.success(
            DiagnosticMessage(u16(payload, 0), u16(payload, 2), payload.copyOfRange(4, payload.size)),
        )
    }

    /**
     * 0x8002 / 0x8003 payload: source = payload[0..1], target = payload[2..3],
     * ack/nack code = payload[4] (frame 12). An ACK (0x8002, code 0x00) only means the
     * message was accepted/queued -- NOT that the UDS service succeeded.
     */
    data class DiagnosticAck(val sourceAddress: Int, val targetAddress: Int, val code: Int)

    fun parseDiagnosticAck(payload: ByteArray): Result<DiagnosticAck> {
        if (payload.size < 5) {
            return Result.failure(IllegalArgumentException("Diagnostic ack too short: ${payload.size}"))
        }
        return Result.success(DiagnosticAck(u16(payload, 0), u16(payload, 2), payload[4].toInt() and 0xFF))
    }

    fun describeNack(code: Int): String = when (code) {
        0x02 -> "invalid source address"
        0x03 -> "unknown target address"
        0x04 -> "diagnostic message too large"
        0x05 -> "out of memory"
        0x06 -> "target unreachable"
        0x07 -> "unknown network"
        0x08 -> "transport protocol error"
        else -> "code 0x%02X".format(code)
    }

    /**
     * 0x0004 vehicle announcement / vehicle identification response. Payload (min 32 bytes):
     *   payload[0..16]  (frame 8..24)  VIN, 17 ASCII bytes
     *   payload[17..18] (frame 25..26) logical address
     *   payload[19..24] (frame 27..32) EID (6 bytes)
     *   payload[25..30] (frame 33..38) GID (6 bytes)
     *   payload[31]     (frame 39)     further action required
     *   payload[32]     (frame 40)     optional VIN/GID sync status
     */
    data class VehicleAnnouncement(val vin: String, val logicalAddress: Int, val eid: ByteArray, val gid: ByteArray)

    fun parseVehicleAnnouncement(payload: ByteArray): Result<VehicleAnnouncement> {
        if (payload.size < 32) {
            return Result.failure(IllegalArgumentException("Vehicle announcement too short: ${payload.size}"))
        }
        return Result.success(
            VehicleAnnouncement(
                vin = String(payload, 0, 17, Charsets.US_ASCII),
                logicalAddress = u16(payload, 17),
                eid = payload.copyOfRange(19, 25),
                gid = payload.copyOfRange(25, 31),
            ),
        )
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
}
