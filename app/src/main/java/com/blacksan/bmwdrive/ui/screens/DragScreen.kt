package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacksan.bmwdrive.settings.SpeedUnit
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.accents

/** 0-50 / 0-100 / 100-200 km/h and 1/4-mile timers triggered from telemetry speed. */
@Composable
fun DragScreen(
    theme: AppTheme,
    source: TelemetrySource,
    onBack: (() -> Unit)? = null,
    speedUnit: SpeedUnit = SpeedUnit.KMH,
) {
    TelemetryEffect(source)
    val s by source.state.collectAsStateWithLifecycle()
    val accents = theme.accents
    var run by remember { mutableStateOf(DragRun()) }

    LaunchedEffect(s.timestampMs) { run = run.step(s.speedKmh, s.timestampMs) }

    val (status, statusColor) = when (run.phase) {
        DragPhase.WAIT_STOP -> "Come to a stop" to MaterialTheme.colorScheme.onSurfaceVariant
        DragPhase.ARMED -> "READY - launch when ready" to accents.good
        DragPhase.RUNNING -> "RUNNING" to accents.warn
        DragPhase.FINISHED -> "DONE" to accents.primary
    }

    DashPage("Drag", onBack) {
        Text(status, color = statusColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            fmt(s.speedKmh?.let { speedUnit.fromKmh(it) }, 0, " ${speedUnit.label}"),
            fontSize = 64.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Text(
            fmtSeconds(run.elapsedMs()),
            fontSize = 40.sp, fontWeight = FontWeight.Bold, color = accents.primary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Text(
            "Distance: %.0f m".format(run.distanceM),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DragMark.entries.forEach { mark ->
                SplitRow(mark.label, fmtSeconds(run.splits[mark]), done = run.splits.containsKey(mark))
            }
        }
        Button(
            onClick = { run = DragRun() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Reset") }
    }
}

@Composable
private fun SplitRow(label: String, value: String, done: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                value, fontSize = 24.sp,
                fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
