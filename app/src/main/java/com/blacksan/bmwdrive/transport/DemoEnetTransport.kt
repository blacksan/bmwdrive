package com.blacksan.bmwdrive.transport

import com.blacksan.bmwdrive.transport.EnetTransport.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simulator transport: returns canned positive UDS responses so the app works with no
 * hardware. Operates at the UDS level (the DoIP framing is irrelevant without a socket).
 */
class DemoEnetTransport(
    override val config: EnetConfig = EnetConfig(demoMode = true, mode = EnetMode.DEMO),
) : EnetTransport {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    override suspend fun connect(config: EnetConfig): Result<Unit> {
        _state.value = LinkState.SearchingInterface
        delay(250)
        _state.value = LinkState.Activating
        delay(250)
        _state.value = LinkState.Connected("demo (simulated)")
        return Result.success(Unit)
    }

    override suspend fun sendUds(request: ByteArray, targetAddress: Int): Result<ByteArray> {
        if (_state.value !is LinkState.Connected) return Result.failure(DoipException("Not connected"))
        if (request.isEmpty()) return Result.failure(IllegalArgumentException("Empty UDS request"))
        delay(30)
        val sid = request[0].toInt() and 0xFF
        val resp: ByteArray = when (sid) {
            0x10 -> byteArrayOf(0x50, request.getOrElse(1) { 0x01 }, 0x00, 0x32, 0x01, 0xF4.toByte())
            0x3E -> byteArrayOf(0x7E, 0x00)
            0x14 -> byteArrayOf(0x54)
            0x19 -> demoDtcs(targetAddress)
            0x22 -> if (request.size >= 3) byteArrayOf(0x62, request[1], request[2], 0x12, 0x34) else nrc(sid, 0x13)
            0x01 -> if (request.size >= 2) demoPid(request[1].toInt() and 0xFF) else nrc(sid, 0x13)
            0x27 -> nrc(sid, 0x11) // security access not simulated
            else -> nrc(sid, 0x11)
        }
        return Result.success(resp)
    }

    override suspend fun disconnect() {
        _state.value = LinkState.Idle
    }

    private fun nrc(sid: Int, code: Int) = byteArrayOf(0x7F, sid.toByte(), code.toByte())

    /** 59 02 <availabilityMask> then 4-byte records (DTC high, mid, low, status). */
    private fun demoDtcs(target: Int): ByteArray {
        val head = byteArrayOf(0x59, 0x02, 0xFF.toByte())
        return when (target) {
            0x0012 -> head + byteArrayOf(0x20, 0xF2.toByte(), 0x20, 0x2F)
            else -> head
        }
    }

    /** OBD-II mode 01 sample values: 0x0C rpm (raw = rpm*4), 0x0D km/h, 0x05 coolant (raw = degC+40). */
    private fun demoPid(pid: Int): ByteArray = when (pid) {
        0x0C -> byteArrayOf(0x41, 0x0C, 0x25, 0x80.toByte()) // 0x2580 = 9600 -> 2400 rpm
        0x0D -> byteArrayOf(0x41, 0x0D, 0x3C)                 // 60 km/h
        0x05 -> byteArrayOf(0x41, 0x05, 0x82.toByte())        // 0x82 = 130 -> 90 degC
        else -> nrc(0x01, 0x31)
    }
}
