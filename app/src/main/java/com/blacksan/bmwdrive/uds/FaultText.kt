package com.blacksan.bmwdrive.uds

/**
 * DTC number -> text decoder. The table is deliberately small and structured for
 * extension: add entries to [builtIn] or call [register] at runtime (e.g. from a
 * bundled asset). Unknown codes fall back to "Unknown DTC 0xXXXXXX".
 */
object FaultText {

    /** Known codes. Descriptions carry their own confidence note. */
    private val builtIn: Map<Int, String> = mapOf(
        // Per the owner's notes: relates to the coolant / HMM (heat management module) circuit.
        // UNVERIFIED -- exact wording not confirmed against BMW documentation.
        0x20F220 to "Coolant / heat management module (HMM) fault (per owner notes; unverified)",
    )

    private val extra = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Add or override entries (24-bit DTC -> description). */
    fun register(entries: Map<Int, String>) {
        extra.putAll(entries)
    }

    fun describe(code: Int): String {
        val c = code and 0xFFFFFF
        return extra[c] ?: builtIn[c] ?: "Unknown DTC 0x%06X".format(c)
    }

    /** UDS DTC status byte (ISO 14229 statusOfDTC) bits, for display. */
    fun describeStatus(status: Int): String {
        val flags = buildList {
            if (status and 0x01 != 0) add("testFailed")
            if (status and 0x02 != 0) add("failedThisCycle")
            if (status and 0x04 != 0) add("pending")
            if (status and 0x08 != 0) add("confirmed")
            if (status and 0x10 != 0) add("notCompletedSinceClear")
            if (status and 0x20 != 0) add("failedSinceClear")
            if (status and 0x40 != 0) add("notCompletedThisCycle")
            if (status and 0x80 != 0) add("warningIndicator")
        }
        return if (flags.isEmpty()) "none" else flags.joinToString(", ")
    }
}
