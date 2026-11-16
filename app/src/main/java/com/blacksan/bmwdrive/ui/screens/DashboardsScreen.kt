package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.blacksan.bmwdrive.ui.nav.Dashboards
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.accents

private data class DashEntry(val route: String, val title: String, val blurb: String, val icon: ImageVector)

private val entries = listOf(
    DashEntry(Dashboards.REDLINE, "Redline", "Tach, shift lights, gear", Icons.Filled.Speed),
    DashEntry(Dashboards.GFORCE, "G-Force", "Friction circle + peak", Icons.Filled.TripOrigin),
    DashEntry(Dashboards.DRAG, "Drag", "0-100, 100-200, 1/4 mile", Icons.Filled.Timer),
    DashEntry(Dashboards.DYNO, "Dyno", "Estimated power + torque", Icons.Filled.ShowChart),
    DashEntry(Dashboards.WARMUP, "Warm-Up", "Oil/coolant + safe revs", Icons.Filled.DeviceThermostat),
)

/**
 * Track dashboards hub: a grid of cards, one per dashboard.
 *
 * @param onOpen called with a [Dashboards] route when a card is tapped.
 */
@Composable
fun DashboardsScreen(theme: AppTheme, onOpen: (String) -> Unit = {}) {
    val accents = theme.accents
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Track", style = MaterialTheme.typography.headlineMedium)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries) { e ->
                Card(Modifier.fillMaxWidth().heightIn(min = 140.dp).clickable { onOpen(e.route) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(e.icon, contentDescription = null, tint = accents.primary, modifier = Modifier.padding(bottom = 4.dp))
                        Text(e.title, style = MaterialTheme.typography.titleLarge)
                        Text(e.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
