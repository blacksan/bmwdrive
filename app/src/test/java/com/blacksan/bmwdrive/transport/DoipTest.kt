package com.blacksan.bmwdrive.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoipTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun routingActivationRequestBytes() {
        // header: 02 FD 00 05 | len 00 00 00 07 ; payload: 0E 80 | 00 | 00 00 00 00
        val f = Doip.buildRoutingActivationRequest(0x0E80)
        assertArrayEquals(bytes(0x02, 0xFD, 0x00, 0x05, 0, 0, 0, 7, 0x0E, 0x80, 0x00, 0, 0, 0, 0), f)
    }

    @Test fun vehicleIdRequestIsHeaderOnly() {
        assertArrayEquals(bytes(0x02, 0xFD, 0x00, 0x01, 0, 0, 0, 0), Doip.buildVehicleIdRequest())
    }

    @Test fun diagnosticMessageLayout() {
        val f = Doip.buildDiagnosticMessage(0x0E80, 0x0012, bytes(0x22, 0xF1, 0x90))
        assertArrayEquals(bytes(0x02, 0xFD, 0x80, 0x01, 0, 0, 0, 7, 0x0E, 0x80, 0x00, 0x12, 0x22, 0xF1, 0x90), f)
        val frame = Doip.parseFrame(f).getOrThrow()
        assertEquals(Doip.Type.DIAGNOSTIC_MESSAGE, frame.payloadType)
        val m = Doip.parseDiagnosticMessage(frame.payload).getOrThrow()
        assertEquals(0x0E80, m.sourceAddress)
        assertEquals(0x0012, m.targetAddress)
        assertArrayEquals(bytes(0x22, 0xF1, 0x90), m.userData)
    }

    @Test fun parsesRoutingActivationSuccess() {
        val p = bytes(0x0E, 0x80, 0x00, 0x10, 0x10, 0, 0, 0, 0)
        val r = Doip.parseRoutingActivationResponse(p).getOrThrow()
        assertEquals(0x0E80, r.testerAddress)
        assertEquals(0x0010, r.entityAddress)
        assertTrue(r.isSuccess)
        assertFalse(Doip.parseRoutingActivationResponse(bytes(0x0E, 0x80, 0x00, 0x10, 0x00, 0, 0, 0, 0)).getOrThrow().isSuccess)
    }

    @Test fun rejectsBadHeader() {
        assertTrue(Doip.parseHeader(bytes(0x02, 0xFE, 0, 5, 0, 0, 0, 0)).isFailure)
        assertTrue(Doip.parseHeader(bytes(0x02, 0xFD, 0, 5)).isFailure)
        assertTrue(Doip.parseHeader(bytes(0x02, 0xFD, 0, 5, 0x7F, 0xFF, 0xFF, 0xFF)).isFailure)
    }

    @Test fun parsesAckAndAnnouncement() {
        val ack = Doip.parseDiagnosticAck(bytes(0x00, 0x12, 0x0E, 0x80, 0x00)).getOrThrow()
        assertEquals(0x0012, ack.sourceAddress)
        assertEquals(0, ack.code)
        val p = ByteArray(33)
        "WBA12345678901234".toByteArray().copyInto(p)
        p[17] = 0x00; p[18] = 0x10
        val a = Doip.parseVehicleAnnouncement(p).getOrThrow()
        assertEquals("WBA12345678901234", a.vin)
        assertEquals(0x0010, a.logicalAddress)
    }
}
