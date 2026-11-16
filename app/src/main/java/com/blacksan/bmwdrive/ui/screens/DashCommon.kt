package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * Starts [source] while this composable is in the composition and stops it on exit.
 * Our sources return from start() immediately, so this simply brackets their lifetime.
 */
@Composable
internal fun TelemetryEffect(source: TelemetrySource, hz: Int = 10) {
    LaunchedEffect(source) {
        source.start(hz)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { source.stop() }
        }
    }
}

/** Standard dashboard page: back button + title row above [content]. */
@Composable
internal fun DashPage(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        content()
    }
}
