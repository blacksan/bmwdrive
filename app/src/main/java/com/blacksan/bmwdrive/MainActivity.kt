package com.blacksan.bmwdrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.blacksan.bmwdrive.ui.nav.Dest
import com.blacksan.bmwdrive.ui.screens.ConnectScreen
import com.blacksan.bmwdrive.ui.screens.DashboardsScreen
import com.blacksan.bmwdrive.ui.screens.DeployScreen
import com.blacksan.bmwdrive.ui.screens.FaultScannerScreen
import com.blacksan.bmwdrive.ui.screens.SettingsScreen
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.BmwDriveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Theme selection is lifted to the Settings screen; wired to DataStore later.
            var theme by remember { mutableStateOf(AppTheme.DEFAULT) }
            BmwDriveTheme(theme = theme) {
                BmwDriveRoot(
                    theme = theme,
                    onThemeChange = { theme = it },
                )
            }
        }
    }
}

@Composable
private fun BmwDriveRoot(
    theme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.bottomBar.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(Dest.CONNECT.route)
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.CONNECT.route,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Dest.CONNECT.route) { ConnectScreen() }
            composable(Dest.DASHBOARDS.route) { DashboardsScreen(theme = theme) }
            composable(Dest.FAULTS.route) { FaultScannerScreen() }
            composable(Dest.DEPLOY.route) { DeployScreen() }
            composable(Dest.SETTINGS.route) {
                SettingsScreen(theme = theme, onThemeChange = onThemeChange)
            }
        }
    }
}
