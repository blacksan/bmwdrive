package com.blacksan.bmwdrive.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Transport contract for reaching the vehicle over the ENET / USB-C Ethernet link.
 *
 * Over an ENET OBD cable the phone joins the car's diagnostic Ethernet and speaks
 * DoIP (ISO 13400) on TCP/UDP 13400. A concrete implementation is responsible for:
 *   1. locating the ENET network interface (USB-C Ethernet adapter / ENET-USB cable),
 *   2. DoIP vehicle identification + routing activation against the gateway (ZGW),
 *   3. framing/among DoIP diagnostic messages (payload type 0x8001) around raw UDS.
 *
 * The UDS layer ([com.blacksan.bmwdrive.uds]) sits on top of this and never deals
 * with sockets or DoIP headers directly.
 */
interface EnetTransport {

    val state: StateFlow<LinkState>

    /** DoIP endpoint + addressing to use. See [EnetConfig] for defaults. */
    val config: EnetConfig

    /**
     * Establish the DoIP link: open the TCP socket to the gateway, send a routing
     * activation request, and await a positive activation response.
     *
     * @return [Result.success] once routing is active, or a failure describing the
     *   stage that failed (no interface, no route, activation refused, timeout).
     */
    suspend fun connect(config: EnetConfig = this.config): Result<Unit>

    /**
     * Send one UDS payload to [targetAddress] and await the matching DoIP diagnostic
     * response, transparently absorbing the 0x78 (responsePending) NRCs.
     *
     * @param request raw UDS bytes beginning with the service id.
     * @param targetAddress logical ECU address (e.g. 0x0010 ZGW, 0x0012 DME).
     */
    suspend fun sendUds(request: ByteArray, targetAddress: Int): Result<ByteArray>

    /** Tear down routing activation and close the socket. Idempotent. */
    suspend fun disconnect()

    sealed interface LinkState {
        data object Idle : LinkState
        data object SearchingInterface : LinkState
        data object Activating : LinkState
        data class Connected(val gatewayIp: String) : LinkState
        data class Error(val reason: String) : LinkState
    }
}

/**
 * DoIP endpoint + addressing. Defaults are the community-standard BMW ENET values;
 * the research spec may refine these and the Settings screen can override them.
 */
data class EnetConfig(
    val gatewayIp: String = DEFAULT_GATEWAY_IP,
    val doipPort: Int = DOIP_PORT,
    /** Tester (phone) logical source address. */
    val sourceAddress: Int = DEFAULT_TESTER_ADDRESS,
    /** Gateway (ZGW) logical target address for routing activation. */
    val gatewayAddress: Int = DEFAULT_GATEWAY_ADDRESS,
    val routingActivationTimeoutMs: Long = 2_000,
    val responseTimeoutMs: Long = 5_000,
    /** When true, run against the in-app simulator instead of real hardware. */
    val demoMode: Boolean = false,
) {
    companion object {
        const val DOIP_PORT = 13400
        const val DEFAULT_GATEWAY_IP = "160.48.199.99"
        const val DEFAULT_TESTER_ADDRESS = 0x0E00
        const val DEFAULT_GATEWAY_ADDRESS = 0x0010
    }
}
