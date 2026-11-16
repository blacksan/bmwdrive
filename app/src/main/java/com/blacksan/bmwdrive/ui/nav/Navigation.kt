package com.blacksan.bmwdrive.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations in the BMWDrive app. */
enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    CONNECT("connect", "Connect", Icons.Filled.Link),
    DASHBOARDS("dashboards", "Track", Icons.Filled.Speed),
    FAULTS("faults", "Faults", Icons.Filled.Build),
    DEPLOY("deploy", "Deploy", Icons.Filled.CloudUpload),
    SETTINGS("settings", "Settings", Icons.Filled.Settings);

    companion object {
        /** Destinations shown in the bottom navigation bar, in order. */
        val bottomBar = listOf(CONNECT, DASHBOARDS, FAULTS, DEPLOY, SETTINGS)
    }
}

/** Sub-routes for the individual track dashboards reached from [Dest.DASHBOARDS]. */
object Dashboards {
    const val REDLINE = "dash/redline"
    const val GFORCE = "dash/gforce"
    const val DRAG = "dash/drag"
    const val DYNO = "dash/dyno"
    const val WARMUP = "dash/warmup"
}
