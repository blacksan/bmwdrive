package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacksan.bmwdrive.ui.screens.preview.DemoEcuBook
import com.blacksan.bmwdrive.ui.screens.preview.FakeUdsClient
import com.blacksan.bmwdrive.uds.EcuAddress
import com.blacksan.bmwdrive.uds.FaultCode
import com.blacksan.bmwdrive.uds.UdsClient
import kotlinx.coroutines.launch

/** Groups faults by ECU, ordered by logical address. */
fun groupFaultsByEcu(faults: List<FaultCode>): List<Pair<EcuAddress, List<FaultCode>>> =
    faults.groupBy { it.ecu.logicalAddress }
        .toSortedMap()
        .values
        .map { it.first().ecu to it }

/**
 * Scan-all faults, grouped per ECU with decoded text and hex code. Clearing is guarded
 * behind a confirmation dialog because it can restart control units and reboot the head unit.
 *
 * @param client UDS client (defaults to a [FakeUdsClient] for demo/preview).
 * @param book ECU address book to scan.
 */
@Composable
fun FaultScannerScreen(
    client: UdsClient = remember { FakeUdsClient() },
    book: List<EcuAddress> = DemoEcuBook,
) {
    val scope = rememberCoroutineScope()
    var faults by remember { mutableStateOf<List<FaultCode>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun scan() {
        scope.launch {
            busy = true; error = null
            client.scanAll(book)
                .onSuccess { faults = it }
                .onFailure { error = it.message ?: "Scan failed" }
            busy = false
        }
    }

    fun clearAll() {
        val current = faults ?: return
        scope.launch {
            busy = true; error = null
            var failure: Throwable? = null
            for ((ecu, _) in groupFaultsByEcu(current)) {
                client.clearFaults(ecu, functional = false).onFailure { failure = it }
            }
            failure?.let { error = it.message ?: "Clear failed" }
            client.scanAll(book).onSuccess { faults = it }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fault Scanner", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::scan, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("  Scan all")
            }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !busy && !faults.isNullOrEmpty(),
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Text("  Clear faults")
            }
            if (busy) CircularProgressIndicator()
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val list = faults
        when {
            list == null -> Text("Tap Scan all to read every control unit.", style = MaterialTheme.typography.bodyLarge)
            list.isEmpty() -> Text("No faults stored.", style = MaterialTheme.typography.bodyLarge)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groupFaultsByEcu(list).forEach { (ecu, codes) ->
                    item(key = ecu.logicalAddress) { EcuFaultCard(ecu, codes) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Clear all faults?") },
            text = {
                Text(
                    "Clearing fault memory across modules can restart control units and reboot the " +
                        "head unit. Do not clear while driving. Stored fault history will be lost.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmClear = false; clearAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear faults") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EcuFaultCard(ecu: EcuAddress, codes: List<FaultCode>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ecu.shortName} - ${ecu.label}", style = MaterialTheme.typography.titleMedium)
            codes.forEach { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(f.hex, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(f.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
