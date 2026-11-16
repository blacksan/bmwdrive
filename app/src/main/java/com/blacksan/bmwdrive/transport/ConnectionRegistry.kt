package com.blacksan.bmwdrive.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide handle to the transport the Connect screen created, so other screens
 * (faults, telemetry) can build a UdsClient over the live link. Null when not set up.
 */
object ConnectionRegistry {
    private val _transport = MutableStateFlow<EnetTransport?>(null)
    val transport: StateFlow<EnetTransport?> = _transport.asStateFlow()

    fun publish(t: EnetTransport?) {
        _transport.value = t
    }
}
