package com.blacksan.bmwdrive.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blacksan.bmwdrive.transport.ConnectionRegistry
import com.blacksan.bmwdrive.transport.DemoEnetTransport
import com.blacksan.bmwdrive.transport.DoipDiscovery
import com.blacksan.bmwdrive.transport.EnetConfig
import com.blacksan.bmwdrive.transport.EnetMode
import com.blacksan.bmwdrive.transport.EnetTransport
import com.blacksan.bmwdrive.transport.EnetTransport.LinkState
import com.blacksan.bmwdrive.transport.RealEnetTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds form state and the transport so the link survives recomposition / tab switches. */
class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    var mode by mutableStateOf(EnetMode.DIRECT)
    var bridgeIp by mutableStateOf(EnetConfig.DEFAULT_BRIDGE_IP)
    var bridgePort by mutableStateOf(EnetConfig.DEFAULT_BRIDGE_PORT.toString())
    var testerHex by mutableStateOf("0E80")
    var gatewayHex by mutableStateOf("0010")
    var discoverStatus by mutableStateOf<String?>(null)
    var discovering by mutableStateOf(false)
    var formError by mutableStateOf<String?>(null)

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private var transport: EnetTransport? = null
    private var mirrorJob: Job? = null

    private fun parseHex(s: String): Int? =
        s.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFFFF }

    private fun buildConfig(): EnetConfig? {
        val tester = parseHex(testerHex)
        val gateway = parseHex(gatewayHex)
        val port = bridgePort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        formError = when {
            tester == null -> "Tester address must be hex, e.g. 0E80"
            gateway == null -> "Gateway address must be hex, e.g. 0010"
            mode == EnetMode.BRIDGE && port == null -> "Port must be 1-65535"
            mode == EnetMode.BRIDGE && bridgeIp.isBlank() -> "Enter the bridge IP"
            else -> null
        }
        if (formError != null) return null
        return EnetConfig(
            sourceAddress = tester!!,
            gatewayAddress = gateway!!,
            mode = mode,
            demoMode = mode == EnetMode.DEMO,
            bridgeIp = bridgeIp.trim(),
            bridgePort = port ?: EnetConfig.DEFAULT_BRIDGE_PORT,
        )
    }

    fun connect() {
        val cfg = buildConfig() ?: return
        val t: EnetTransport = if (cfg.effectiveMode == EnetMode.DEMO) {
            DemoEnetTransport(cfg)
        } else {
            RealEnetTransport(cfg, getApplication<Application>())
        }
        viewModelScope.launch {
            transport?.disconnect()
            mirrorJob?.cancel()
            transport = t
            ConnectionRegistry.publish(t)
            mirrorJob = launch { t.state.collect { _link.value = it } }
            t.connect(cfg) // outcome is reflected through t.state
        }
    }

    fun disconnect() {
        val t = transport ?: return
        viewModelScope.launch { t.disconnect() }
    }

    fun discover() {
        if (discovering) return
        discovering = true
        discoverStatus = "Searching (UDP 13400)..."
        viewModelScope.launch {
            val targets = buildList {
                if (mode == EnetMode.BRIDGE && bridgeIp.isNotBlank()) add(bridgeIp.trim())
                add(DoipDiscovery.BROADCAST)
            }
            val r = DoipDiscovery.discover(targets, timeoutMs = 2_500)
            r.onSuccess {
                discoverStatus = "Found gateway ${it.ip} (VIN ${it.announcement.vin}, " +
                    "address 0x%04X)".format(it.announcement.logicalAddress)
                if (mode == EnetMode.BRIDGE) bridgeIp = it.ip
            }
            r.onFailure {
                discoverStatus = "Nothing answered. Many bridges do not forward UDP -- enter the IP manually."
            }
            discovering = false
        }
    }

    override fun onCleared() {
        // Leave the link up if the app process lives on; just stop mirroring.
        mirrorJob?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen() {
    val vm: ConnectViewModel = viewModel()
    val link by vm.link.collectAsStateWithLifecycle()
    val busy = link is LinkState.SearchingInterface || link is LinkState.Activating
    val connected = link is LinkState.Connected

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to car", style = MaterialTheme.typography.headlineSmall)

        StatusCard(link)

        Text("Mode", style = MaterialTheme.typography.titleMedium)
        val modes = EnetMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = vm.mode == m,
                    onClick = { vm.mode = m },
                    enabled = !connected && !busy,
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(
                        when (m) {
                            EnetMode.BRIDGE -> "Bridge"
                            EnetMode.DIRECT -> "Direct"
                            EnetMode.DEMO -> "Demo"
                        },
                    )
                }
            }
        }
        Text(
            when (vm.mode) {
                EnetMode.DIRECT -> "USB-C Ethernet tethering, no root -- the same way BimmerLink connects. " +
                    "Plug the ENET cable into the phone; works if the phone supports the adapter's chipset."
                EnetMode.BRIDGE -> "Fallback: phone joins the WiFi of an ENET-to-WiFi bridge on the car's " +
                    "OBD ENET cable. Use if the phone won't bring up the USB-C adapter."
                EnetMode.DEMO -> "Simulated car. No hardware needed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val editable = !connected && !busy
        if (vm.mode == EnetMode.BRIDGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = vm.bridgeIp,
                    onValueChange = { vm.bridgeIp = it },
                    label = { Text("Bridge IP") },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(2f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = vm.bridgePort,
                    onValueChange = { vm.bridgePort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        if (vm.mode != EnetMode.DEMO) {
            OutlinedButton(
                onClick = vm::discover,
                enabled = editable && !vm.discovering,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.discovering) "Discovering..." else "Discover gateway (UDP vehicle ID)") }
            vm.discoverStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = vm.testerHex,
                onValueChange = { vm.testerHex = it.take(6) },
                label = { Text("Tester addr (hex)") },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = vm.gatewayHex,
                onValueChange = { vm.gatewayHex = it.take(6) },
                label = { Text("Gateway addr (hex)") },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
        }

        vm.formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (connected || busy) {
            Button(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Cancel" else "Disconnect")
            }
        } else {
            Button(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(
                "Direct vs bridge: most modern phones bring up a USB-C Ethernet adapter as a wired " +
                    "network, and this app binds to it with no root (how BimmerLink works). If your phone " +
                    "lacks the adapter's chipset driver and won't show the wired link, switch to Bridge " +
                    "mode and put an ENET-to-WiFi bridge on the car's ENET cable instead.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(link: LinkState) {
    val (title, detail) = when (link) {
        LinkState.Idle -> "Disconnected" to "Not connected."
        LinkState.SearchingInterface -> "Searching..." to "Looking for the gateway / network interface."
        LinkState.Activating -> "Activating..." to "TCP open; DoIP routing activation in progress."
        is LinkState.Connected -> "Connected" to "Gateway ${link.gatewayIp}. Routing active."
        is LinkState.Error -> "Error" to link.reason
    }
    val container = when (link) {
        is LinkState.Connected -> MaterialTheme.colorScheme.primaryContainer
        is LinkState.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
