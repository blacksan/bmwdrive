package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blacksan.bmwdrive.deploy.DemoHeadUnitDeployer
import com.blacksan.bmwdrive.deploy.DeployEvent
import com.blacksan.bmwdrive.deploy.HeadUnitDeployer
import com.blacksan.bmwdrive.deploy.SshHeadUnitDeployer
import com.blacksan.bmwdrive.deploy.SshTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class DeployAction(val title: String, val destructive: Boolean) {
    CHECK("Check", false),
    INSTALL("Install", true),
    RESTORE("Restore", true),
    STATUS("Status", false),
}

data class DeployUiState(
    val host: String = "",
    val port: String = "22",
    val username: String = "root",
    val password: String = "",
    val useKey: Boolean = false,
    val keyPath: String = "",
    val kitPath: String = "",
    val useRealSsh: Boolean = false,
    val ackRoute: Boolean = false,
    val ackRoot: Boolean = false,
    val ackSdk: Boolean = false,
    val running: Boolean = false,
    val pendingConfirm: DeployAction? = null,
) {
    val allAcknowledged get() = ackRoute && ackRoot && ackSdk
    private val portNum get() = port.toIntOrNull()
    val formValid
        get() = host.isNotBlank() && portNum in 1..65535 && username.isNotBlank() &&
            (if (useKey) keyPath.isNotBlank() else password.isNotEmpty())
    val canInstall get() = formValid && allAcknowledged && kitPath.isNotBlank() && !running
    val canRestore get() = formValid && allAcknowledged && !running
}

class DeployViewModel : ViewModel() {
    private val demo: HeadUnitDeployer = DemoHeadUnitDeployer()
    private val real: HeadUnitDeployer = SshHeadUnitDeployer()

    private val _state = MutableStateFlow(DeployUiState())
    val state: StateFlow<DeployUiState> = _state.asStateFlow()

    /** Snapshot list so Compose recomposes as events arrive. */
    val log = mutableStateListOf<DeployEvent>()

    private var job: Job? = null

    fun update(f: (DeployUiState) -> DeployUiState) = _state.update(f)

    /** Entry point from the buttons: destructive actions go through confirmation first. */
    fun request(action: DeployAction) {
        val s = _state.value
        if (s.running) return
        when (action) {
            DeployAction.CHECK, DeployAction.STATUS -> if (s.formValid) run(action)
            DeployAction.INSTALL -> if (s.canInstall) _state.update { it.copy(pendingConfirm = action) }
            DeployAction.RESTORE -> if (s.canRestore) _state.update { it.copy(pendingConfirm = action) }
        }
    }

    fun dismissConfirm() = _state.update { it.copy(pendingConfirm = null) }

    fun confirm() {
        val action = _state.value.pendingConfirm ?: return
        _state.update { it.copy(pendingConfirm = null) }
        val s = _state.value
        // Re-check gating so no path can fire a destructive action unacknowledged.
        val ok = when (action) {
            DeployAction.INSTALL -> s.canInstall
            DeployAction.RESTORE -> s.canRestore
            else -> false
        }
        if (ok) run(action)
    }

    fun cancel() {
        job?.cancel()
    }

    private fun run(action: DeployAction) {
        val s = _state.value
        log.clear()
        _state.update { it.copy(running = true) }
        job = viewModelScope.launch {
            try {
                val deployer = if (s.useRealSsh) real else demo
                val target = buildTarget(s)
                val flow = when (action) {
                    DeployAction.CHECK -> deployer.check(target)
                    DeployAction.INSTALL -> deployer.install(target, s.kitPath.trim())
                    DeployAction.RESTORE -> deployer.restore(target)
                    DeployAction.STATUS -> deployer.status(target)
                }
                flow.collect { log.add(it) }
            } catch (e: CancellationException) {
                log.add(DeployEvent.Failed("Cancelled"))
                throw e
            } catch (e: Exception) {
                log.add(DeployEvent.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun buildTarget(s: DeployUiState): SshTarget {
        val pem = if (s.useKey) {
            withContext(Dispatchers.IO) { File(s.keyPath.trim()).readText() }
        } else null
        return SshTarget(
            host = s.host.trim(),
            port = s.port.toInt(),
            username = s.username.trim(),
            privateKeyPem = pem,
            password = if (s.useKey) null else s.password,
        )
    }
}

@Composable
fun DeployScreen() {
    val vm: DeployViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    DeployContent(
        state = state,
        log = vm.log,
        onChange = vm::update,
        onAction = vm::request,
        onCancel = vm::cancel,
    )
    state.pendingConfirm?.let { action ->
        ConfirmDialog(action, onConfirm = vm::confirm, onDismiss = vm::dismissConfirm)
    }
}

@Composable
private fun DeployContent(
    state: DeployUiState,
    log: List<DeployEvent>,
    onChange: ((DeployUiState) -> DeployUiState) -> Unit,
    onAction: (DeployAction) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Head-unit deploy (optional)", style = MaterialTheme.typography.headlineSmall)

        PreconditionsCard(state, onChange)
        TargetForm(state, onChange)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.useRealSsh, onCheckedChange = { c -> onChange { it.copy(useRealSsh = c) } }, enabled = !state.running)
            Text(
                if (state.useRealSsh) "Real SSH to the head unit" else "Demo mode (scripted, no network)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        ActionRow(state, onAction, onCancel)
        LogPane(log, running = state.running)
    }
}

@Composable
private fun PreconditionsCard(state: DeployUiState, onChange: ((DeployUiState) -> DeployUiState) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Hard preconditions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "This app cannot provide any of these. Tick each to confirm you have them; " +
                    "Install and Restore stay disabled until all three are acknowledged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            AckRow(
                checked = state.ackRoute,
                onChecked = { c -> onChange { it.copy(ackRoute = c) } },
                text = "I have a network ROUTE from this phone to the head unit's own Ethernet. " +
                    "The ENET OBD cable reaches the DoIP gateway, NOT head-unit SSH.",
            )
            AckRow(
                checked = state.ackRoot,
                onChecked = { c -> onChange { it.copy(ackRoot = c) } },
                text = "I already have ROOT shell access on the MGU (not provided by this app).",
            )
            AckRow(
                checked = state.ackSdk,
                onChecked = { c -> onChange { it.copy(ackSdk = c) } },
                text = "I have BMW's oap-sdk, extracted from my own unit, inside the kit.",
            )
        }
    }
}

@Composable
private fun AckRow(checked: Boolean, onChecked: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun TargetForm(state: DeployUiState, onChange: ((DeployUiState) -> DeployUiState) -> Unit) {
    val enabled = !state.running
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.host,
                onValueChange = { v -> onChange { it.copy(host = v) } },
                label = { Text("Host") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = { v -> onChange { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                label = { Text("Port") },
                singleLine = true,
                enabled = enabled,
                isError = state.port.toIntOrNull() !in 1..65535,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.4f),
            )
        }
        OutlinedTextField(
            value = state.username,
            onValueChange = { v -> onChange { it.copy(username = v) } },
            label = { Text("Username") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !state.useKey,
                onClick = { onChange { it.copy(useKey = false) } },
                label = { Text("Password") },
                enabled = enabled,
            )
            FilterChip(
                selected = state.useKey,
                onClick = { onChange { it.copy(useKey = true) } },
                label = { Text("Use key") },
                enabled = enabled,
            )
        }
        if (state.useKey) {
            OutlinedTextField(
                value = state.keyPath,
                onValueChange = { v -> onChange { it.copy(keyPath = v) } },
                label = { Text("Private key file path (PEM)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = state.password,
                onValueChange = { v -> onChange { it.copy(password = v) } },
                label = { Text("Password") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.kitPath,
            onValueChange = { v -> onChange { it.copy(kitPath = v) } },
            label = { Text("Kit path on this phone (needed for Install)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActionRow(state: DeployUiState, onAction: (DeployAction) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onAction(DeployAction.CHECK) },
                enabled = state.formValid && !state.running,
                modifier = Modifier.weight(1f),
            ) { Text("Check") }
            OutlinedButton(
                onClick = { onAction(DeployAction.STATUS) },
                enabled = state.formValid && !state.running,
                modifier = Modifier.weight(1f),
            ) { Text("Status") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onAction(DeployAction.INSTALL) },
                enabled = state.canInstall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Install") }
            Button(
                onClick = { onAction(DeployAction.RESTORE) },
                enabled = state.canRestore,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Restore") }
        }
        if (!state.allAcknowledged) {
            Text(
                "Install and Restore are locked until all preconditions are ticked.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.running) {
            TextButton(onClick = onCancel) { Text("Cancel running operation") }
        }
    }
}

@Composable
private fun ConfirmDialog(action: DeployAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${action.title} on the head unit?") },
        text = {
            Text(
                when (action) {
                    DeployAction.INSTALL ->
                        "Install modifies system files on the head unit and restarts the app platform. " +
                            "The unit may reboot or become unresponsive. A backup is taken first, but do not " +
                            "do this while driving or while the car is in use."
                    else ->
                        "Restore reverts touched files to the first (stock) backup and may restart or " +
                            "reboot the head unit. The unit will ask for YES; this confirmation supplies it."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(action.title, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LogPane(log: List<DeployEvent>, running: Boolean) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (running) "Log (running...)" else "Log", style = MaterialTheme.typography.titleSmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            if (log.isEmpty()) {
                Text("No output yet.", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(log.toList()) { e -> LogRow(e) }
            }
        }
    }
}

@Composable
private fun LogRow(e: DeployEvent) {
    when (e) {
        is DeployEvent.Stage -> Text(
            "[${e.index}/${e.total}] ${e.name}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        is DeployEvent.Line -> Text(
            e.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is DeployEvent.Failed -> Text(
            "FAILED: ${e.reason}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        DeployEvent.Done -> Text(
            "Done.",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
