package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacksan.bmwdrive.settings.AppSettings
import com.blacksan.bmwdrive.settings.DoipSettings
import com.blacksan.bmwdrive.settings.SettingsRepository
import com.blacksan.bmwdrive.settings.SpeedUnit
import com.blacksan.bmwdrive.ui.theme.AppTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Parses the DoIP form fields, or returns null if any is invalid. Tester address is hex. */
fun parseDoip(ip: String, port: String, tester: String): DoipSettings? {
    val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val t = tester.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFFFF } ?: return null
    val host = ip.trim().takeIf { it.isNotEmpty() && ' ' !in it } ?: return null
    return DoipSettings(host, p, t)
}

/**
 * Theme picker, unit toggle, demo-mode switch and DoIP config. Values persist through
 * [repository] (DataStore); when it is null the screen is display-only (previews).
 * Theme changes are also reported through [onThemeChange] so the caller can restyle immediately.
 */
@Composable
fun SettingsScreen(
    theme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    repository: SettingsRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val flow = remember(repository) { repository?.settings ?: flowOf(AppSettings(theme = theme)) }
    val settings by flow.collectAsStateWithLifecycle(initialValue = AppSettings(theme = theme))

    var ip by remember { mutableStateOf(settings.doip.gatewayIp) }
    var port by remember { mutableStateOf(settings.doip.port.toString()) }
    var tester by remember { mutableStateOf("%04X".format(settings.doip.testerAddress)) }
    var saved by remember { mutableStateOf(false) }
    // Refresh the form when persisted values arrive (first DataStore emission).
    LaunchedEffect(settings.doip) {
        ip = settings.doip.gatewayIp
        port = settings.doip.port.toString()
        tester = "%04X".format(settings.doip.testerAddress)
    }
    val parsed = parseDoip(ip, port, tester)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        AppTheme.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                    selected = theme == t, role = Role.RadioButton,
                    onClick = {
                        onThemeChange(t)
                        scope.launch { repository?.setTheme(t) }
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == t, onClick = null)
                Text(t.label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        HorizontalDivider()

        Text("Speed unit", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedUnit.entries.forEach { u ->
                FilterChip(
                    selected = settings.speedUnit == u,
                    onClick = { scope.launch { repository?.setSpeedUnit(u) } },
                    label = { Text(u.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        HorizontalDivider()

        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                Text("Replay a synthetic drive and sample faults; no car needed.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.demoMode, onCheckedChange = { scope.launch { repository?.setDemoMode(it) } })
        }
        HorizontalDivider()

        Text("DoIP connection", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ip, onValueChange = { ip = it; saved = false },
            label = { Text("Gateway IP") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it; saved = false },
            label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = tester, onValueChange = { tester = it; saved = false },
            label = { Text("Tester address (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { parsed?.let { d -> scope.launch { repository?.setDoip(d); saved = true } } },
            enabled = parsed != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (saved) "Saved" else "Save connection settings") }
    }
}
