package com.blacksan.bmwdrive.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.blacksan.bmwdrive.transport.EnetTransport.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A DoIP entity found via UDP vehicle identification. */
data class DiscoveredGateway(val ip: String, val announcement: Doip.VehicleAnnouncement)

/** Failure with a user-presentable reason. */
class DoipException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** UDP vehicle identification (0x0001 request -> 0x0004 announcement) on port 13400. */
object DoipDiscovery {
    const val BROADCAST = "255.255.255.255"

    /**
     * Send a vehicle identification request to each of [targets] (unicast IPs and/or the
     * broadcast address) and return the first valid announcement. Runs on Dispatchers.IO.
     * [network], if given, pins the socket to that Network.
     *
     * Note: a bridge may not forward UDP; callers must fall back to a configured IP.
     */
    suspend fun discover(
        targets: List<String>,
        port: Int = Doip.PORT,
        timeoutMs: Long = 1_500,
        network: Network? = null,
    ): Result<DiscoveredGateway> = withContext(Dispatchers.IO) {
        try {
            val request = Doip.buildVehicleIdRequest()
            DatagramSocket().use { sock ->
                sock.broadcast = true
                network?.bindSocket(sock)
                val buf = ByteArray(512)
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                while (true) {
                    val now = System.currentTimeMillis()
                    if (now >= deadline) break
                    if (now - lastSend >= 500) { // (re)send every 500 ms
                        for (t in targets) {
                            try {
                                sock.send(DatagramPacket(request, request.size, InetAddress.getByName(t), port))
                            } catch (ignored: IOException) {
                                // unreachable target (e.g. no broadcast route) -- try the others
                            }
                        }
                        lastSend = now
                    }
                    sock.soTimeout = minOf(250L, deadline - now).toInt().coerceAtLeast(1)
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (ignored: SocketTimeoutException) {
                        continue
                    }
                    val frame = Doip.parseFrame(pkt.data.copyOf(pkt.length)).getOrNull() ?: continue
                    if (frame.payloadType != Doip.Type.VEHICLE_ANNOUNCEMENT) continue
                    val ann = Doip.parseVehicleAnnouncement(frame.payload).getOrNull() ?: continue
                    return@withContext Result.success<DiscoveredGateway>(DiscoveredGateway(pkt.address.hostAddress ?: continue, ann))
                }
            }
            Result.failure<DiscoveredGateway>(DoipException("No DoIP vehicle announcement received within ${timeoutMs} ms"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure<DiscoveredGateway>(DoipException("Discovery failed: ${t.message}", t))
        }
    }
}

/**
 * Real DoIP transport (java.net sockets on Dispatchers.IO; no third-party libs).
 *
 *  - DIRECT (default): USB-C Ethernet tethering, no root. Requests a TRANSPORT_ETHERNET
 *    Network (with NET_CAPABILITY_INTERNET removed, since the car link has no internet) and
 *    binds the process to it -- the same approach BimmerLink uses. Works on a stock phone
 *    whose kernel has the adapter's chipset driver. If the adapter isn't recognised, falls
 *    back with advice to use BRIDGE. NOTE: bindProcessToNetwork routes ALL of the app's
 *    traffic over the (offline) car link until [disconnect].
 *  - BRIDGE: TCP to an ENET-to-WiFi/TCP bridge, for phones that don't bring up the adapter.
 *  - DEMO: not handled here; use [DemoEnetTransport].
 *
 * [context] is only needed for DIRECT mode (ConnectivityManager).
 */
class RealEnetTransport(
    override val config: EnetConfig = EnetConfig(),
    context: Context? = null,
) : EnetTransport {

    private val appContext: Context? = context?.applicationContext
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val connectLock = Mutex()
    private val sendLock = Mutex()
    private val resourceLock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var activeConfig: EnetConfig = config
    private var boundNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override suspend fun connect(config: EnetConfig): Result<Unit> = withContext(Dispatchers.IO) {
        connectLock.withLock {
            teardown()
            activeConfig = config
            try {
                doConnect(config)
                Result.success<Unit>(Unit)
            } catch (ce: CancellationException) {
                teardown()
                _state.value = LinkState.Idle
                throw ce
            } catch (t: Throwable) {
                teardown()
                val reason = t.message ?: t.javaClass.simpleName
                _state.value = LinkState.Error(reason)
                Result.failure<Unit>(if (t is DoipException) t else DoipException(reason, t))
            }
        }
    }

    private suspend fun doConnect(cfg: EnetConfig) {
        _state.value = LinkState.SearchingInterface
        var host: String
        var port = cfg.doipPort
        when (cfg.effectiveMode) {
            EnetMode.DEMO -> throw DoipException("DEMO mode is served by DemoEnetTransport, not RealEnetTransport")
            EnetMode.BRIDGE -> {
                host = cfg.bridgeIp
                port = cfg.bridgePort
                if (cfg.discoverGateway) {
                    // Prefer discovery; fall back to the configured bridge IP silently.
                    DoipDiscovery.discover(
                        listOf(cfg.bridgeIp, DoipDiscovery.BROADCAST), cfg.doipPort, cfg.discoveryTimeoutMs,
                    ).getOrNull()?.let { host = it.ip }
                }
            }
            EnetMode.DIRECT -> {
                val network = acquireEthernet(cfg)
                host = cfg.gatewayIp // UNCONFIRMED fallback; discovery below is preferred
                if (cfg.discoverGateway) {
                    DoipDiscovery.discover(listOf(DoipDiscovery.BROADCAST), cfg.doipPort, cfg.discoveryTimeoutMs, network)
                        .getOrNull()?.let { host = it.ip }
                }
            }
        }

        _state.value = LinkState.Activating
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw DoipException(
                "Could not open TCP to $host:$port (${e.message}). " +
                    if (cfg.effectiveMode == EnetMode.BRIDGE) "Check the phone is on the bridge's WiFi and the IP/port are right."
                    else "Direct ENET needs the OS to expose the adapter -- use BRIDGE mode.",
                e,
            )
        }
        val inp = DataInputStream(s.getInputStream())
        synchronized(resourceLock) {
            socket = s
            input = inp
        }
        routingActivation(s, inp, cfg)
        _state.value = LinkState.Connected(host)
    }

    /** DoIP routing activation: send 0x0005 (source 0x0E80), await 0x0006 with code 0x10. */
    private fun routingActivation(s: Socket, inp: DataInputStream, cfg: EnetConfig) {
        s.getOutputStream().apply {
            write(Doip.buildRoutingActivationRequest(cfg.sourceAddress))
            flush()
        }
        val deadline = System.currentTimeMillis() + cfg.routingActivationTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw DoipException("Routing activation timed out after ${cfg.routingActivationTimeoutMs} ms")
            s.soTimeout = remaining.toInt().coerceAtLeast(1)
            val frame = try {
                readFrame(inp)
            } catch (e: SocketTimeoutException) {
                throw DoipException("Routing activation timed out after ${cfg.routingActivationTimeoutMs} ms", e)
            }
            when (frame.payloadType) {
                Doip.Type.ROUTING_ACTIVATION_RESPONSE -> {
                    val r = Doip.parseRoutingActivationResponse(frame.payload).getOrElse { throw DoipException(it.message ?: "bad activation response", it) }
                    if (!r.isSuccess) {
                        throw DoipException(
                            "Routing activation refused: ${Doip.ActivationCode.describe(r.code)} (0x%02X)".format(r.code),
                        )
                    }
                    return
                }
                Doip.Type.GENERIC_NACK -> throw DoipException("Gateway sent DoIP header NACK during activation")
                else -> Unit // ignore anything else (e.g. alive check) until the 0x0006 arrives
            }
        }
    }

    private suspend fun acquireEthernet(cfg: EnetConfig): Network {
        val ctx = appContext ?: throw DoipException("DIRECT mode needs an Android Context -- use BRIDGE mode.")
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
            ?: throw DoipException("ConnectivityManager unavailable")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // A link-local/Auto-IP car network has no internet; the default request would never match.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val found = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.complete(network)
            }

            override fun onUnavailable() {
                found.completeExceptionally(DoipException("No Ethernet network"))
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    teardown()
                    _state.value = LinkState.Error("Ethernet link lost")
                }
            }
        }
        val timeout = cfg.ethernetWaitMs
        try {
            cm.requestNetwork(request, cb, timeout.toInt())
        } catch (e: SecurityException) {
            throw DoipException("Missing CHANGE_NETWORK_STATE permission: ${e.message}", e)
        }
        val network = try {
            withTimeoutOrNull(timeout + 500) { found.await() }
        } catch (e: DoipException) {
            null
        }
        if (network == null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            throw DoipException(
                "Android did not expose an Ethernet network within ${timeout} ms. Check the ENET " +
                    "cable is seated and the ignition is on; some phones need the USB Ethernet " +
                    "enabled once under Settings. If this phone's kernel lacks the adapter's chipset " +
                    "driver, use BRIDGE mode with an ENET-to-WiFi/TCP bridge instead.",
            )
        }
        synchronized(resourceLock) {
            networkCallback = cb
            boundNetwork = network
        }
        if (!cm.bindProcessToNetwork(network)) {
            throw DoipException("bindProcessToNetwork failed -- use BRIDGE mode.")
        }
        return network
    }

    override suspend fun sendUds(request: ByteArray, targetAddress: Int): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            sendLock.withLock {
                val s = socket
                val inp = input
                if (s == null || inp == null || _state.value !is LinkState.Connected) {
                    return@withLock Result.failure<ByteArray>(DoipException("Not connected"))
                }
                val cfg = activeConfig
                try {
                    exchange(s, inp, cfg, request, targetAddress)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: SocketTimeoutException) {
                    Result.failure<ByteArray>(DoipException("No UDS response within ${cfg.responseTimeoutMs} ms", e))
                } catch (e: IOException) {
                    // Broken stream: drop the link so the UI shows it.
                    teardown()
                    _state.value = LinkState.Error("Link lost: ${e.message}")
                    Result.failure<ByteArray>(DoipException("Link lost: ${e.message}", e))
                }
            }
        }

    /** Blocking request/response exchange. Caller holds [sendLock]. */
    private fun exchange(
        s: Socket,
        inp: DataInputStream,
        cfg: EnetConfig,
        request: ByteArray,
        target: Int,
    ): Result<ByteArray> {
        // Discard stale frames left by an earlier timed-out request.
        while (inp.available() > 0) inp.skip(inp.available().toLong())

        val out = s.getOutputStream()
        out.write(Doip.buildDiagnosticMessage(cfg.sourceAddress, target, request))
        out.flush()

        var deadline = System.currentTimeMillis() + cfg.responseTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("deadline")
            s.soTimeout = remaining.toInt().coerceAtLeast(1)
            val frame = readFrame(inp)
            when (frame.payloadType) {
                // 0x8002: message accepted/queued. NOT the UDS result -- keep reading.
                Doip.Type.DIAGNOSTIC_ACK -> Unit
                Doip.Type.DIAGNOSTIC_NACK -> {
                    val code = Doip.parseDiagnosticAck(frame.payload).getOrNull()?.code
                    return Result.failure(
                        DoipException("Gateway rejected the diagnostic message: ${code?.let { Doip.describeNack(it) } ?: "malformed NACK"}"),
                    )
                }
                Doip.Type.GENERIC_NACK -> return Result.failure(DoipException("Gateway sent DoIP header NACK"))
                Doip.Type.ALIVE_CHECK_REQUEST -> {
                    out.write(Doip.buildAliveCheckResponse(cfg.sourceAddress))
                    out.flush()
                }
                Doip.Type.DIAGNOSTIC_MESSAGE -> {
                    val msg = Doip.parseDiagnosticMessage(frame.payload).getOrNull() ?: continue
                    if (msg.sourceAddress != target) continue // reply from someone else; not ours
                    val uds = msg.userData
                    if (isResponsePending(uds)) {
                        // NRC 0x78: ECU needs more time. Restart the wait window.
                        deadline = System.currentTimeMillis() + cfg.responseTimeoutMs
                        continue
                    }
                    return Result.success(uds)
                }
                else -> Unit
            }
        }
    }

    /** UDS negative response layout: [0]=0x7F, [1]=requested SID, [2]=NRC. */
    private fun isResponsePending(uds: ByteArray): Boolean =
        uds.size >= 3 && (uds[0].toInt() and 0xFF) == 0x7F && (uds[2].toInt() and 0xFF) == 0x78

    /** Read exactly one DoIP frame (8-byte header, then payload) from the TCP stream. */
    private fun readFrame(inp: DataInputStream): Doip.Frame {
        val header = ByteArray(Doip.HEADER_SIZE)
        try {
            inp.readFully(header)
            val h = Doip.parseHeader(header).getOrElse { throw DoipException(it.message ?: "bad DoIP header", it) }
            val payload = ByteArray(h.payloadLength)
            inp.readFully(payload)
            return Doip.Frame(h.payloadType, payload)
        } catch (e: EOFException) {
            throw DoipException("Gateway closed the connection", e)
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            teardown()
            _state.value = LinkState.Idle
        }
    }

    /** Idempotent resource release. Closing the socket also unblocks any in-flight read. */
    private fun teardown() {
        var s: Socket? = null
        var cb: ConnectivityManager.NetworkCallback? = null
        var hadNetwork = false
        synchronized(resourceLock) {
            s = socket; socket = null; input = null
            cb = networkCallback; networkCallback = null
            hadNetwork = boundNetwork != null; boundNetwork = null
        }
        runCatching { s?.close() }
        val ctx = appContext
        if (ctx != null && (cb != null || hadNetwork)) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            if (hadNetwork) runCatching { cm?.bindProcessToNetwork(null) }
            if (cb != null) runCatching { cm?.unregisterNetworkCallback(cb) }
        }
    }

    private companion object {
        const val TCP_CONNECT_TIMEOUT_MS = 3_000
    }
}
