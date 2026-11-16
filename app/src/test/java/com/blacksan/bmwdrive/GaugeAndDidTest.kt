package com.blacksan.bmwdrive

import com.blacksan.bmwdrive.telemetry.DidDecoders
import com.blacksan.bmwdrive.ui.screens.DragMark
import com.blacksan.bmwdrive.ui.screens.DragPhase
import com.blacksan.bmwdrive.ui.screens.DragRun
import com.blacksan.bmwdrive.ui.screens.LedLevel
import com.blacksan.bmwdrive.ui.screens.safeRevLimit
import com.blacksan.bmwdrive.ui.screens.shiftLedStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeAndDidTest {
    @Test fun rpmDecode() = assertEquals(3000, DidDecoders.rpm(byteArrayOf(0x2E, 0xE0.toByte())))
    @Test fun shortPayloadIsNull() = assertNull(DidDecoders.rpm(byteArrayOf(0x01)))
    @Test fun tempDecode() = assertEquals(50f, DidDecoders.tempC(byteArrayOf(90)))

    @Test fun ledsLightProgressively() {
        val leds = shiftLedStates(6500, 3500, 6500)
        assertTrue(leds.all { it != LedLevel.OFF })
        assertEquals(LedLevel.RED, leds.last())
        assertTrue(shiftLedStates(null, 3500, 6500).all { it == LedLevel.OFF })
    }

    @Test fun revLimitRisesWithOil() {
        assertEquals(3500, safeRevLimit(20f))
        assertEquals(6500, safeRevLimit(95f))
        assertNull(safeRevLimit(null))
    }

    @Test fun dragSplits() {
        var run = DragRun()
        // 0 -> 120 km/h at 10 km/h per 100 ms.
        var t = 0L
        run = run.step(0f, t)
        for (v in 10..120 step 10) { t += 100; run = run.step(v.toFloat(), t) }
        assertEquals(DragPhase.RUNNING, run.phase)
        assertEquals(500L, run.splits[DragMark.ZERO_50])
        assertEquals(1000L, run.splits[DragMark.ZERO_100])
    }
}
