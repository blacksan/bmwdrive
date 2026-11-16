package com.blacksan.bmwdrive.uds

import org.junit.Assert.assertEquals
import org.junit.Test

class UdsParsingTest {
    @Test fun parsesDtcRecords() {
        val resp = byteArrayOf(0x59, 0x02, 0xFF.toByte(), 0x20, 0xF2.toByte(), 0x20, 0x2F, 0x12, 0x34, 0x56, 0x08)
        val faults = RealUdsClient.parseDtcResponse(EcuAddressBook.DME, resp)
        assertEquals(2, faults.size)
        assertEquals("20F220", faults[0].hex)
        assertEquals(0x2F, faults[0].statusMask)
        assertEquals("Unknown DTC 0x123456", faults[1].description)
    }

    @Test fun noRecordsIsEmpty() {
        assertEquals(0, RealUdsClient.parseDtcResponse(EcuAddressBook.ZGW, byteArrayOf(0x59, 0x02, 0xFF.toByte())).size)
    }
}
