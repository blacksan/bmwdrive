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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.blacksan.bmwdrive.settings.AppSettings
import com.blacksan.bmwdrive.settings.SettingsRepository
import com.blacksan.bmwdrive.telemetry.DemoTelemetrySource
import com.blacksan.bmwdrive.telemetry.SensorImu
import com.blacksan.bmwdrive.telemetry.UdsTelemetrySource
import com.blacksan.bmwdrive.transport.ConnectionRegistry
import com.blacksan.bmwdrive.uds.EcuAddress
import com.blacksan.bmwdrive.uds.EcuAddressBook
import com.blacksan.bmwdrive.uds.RealUdsClient
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import com.blacksan.bmwdrive.ui.nav.Dashboards
import com.blacksan.bmwdrive.ui.nav.Dest
import com.blacksan.bmwdrive.ui.screens.DragScreen
import com.blacksan.bmwdrive.ui.screens.DynoScreen
import com.blacksan.bmwdrive.ui.screens.GForceScreen
import com.blacksan.bmwdrive.ui.screens.RedlineScreen
import com.blacksan.bmwdrive.ui.screens.WarmUpScreen
import com.blacksan.bmwdrive.ui.screens.preview.DemoEcuBook
import com.blacksan.bmwdrive.ui.screens.preview.FakeUdsClient
import com.blacksan.bmwdrive.uds.UdsClient
import com.blacksan.bmwdrive.ui.screens.ConnectScreen
import com.blacksan.bmwdrive.ui.screens.DashboardsScreen
import com.blacksan.bmwdrive.ui.screens.DeployScreen
import com.blacksan.bmwdrive.ui.screens.FaultScannerScreen
import com.blacksan.bmwdrive.ui.screens.SettingsScreen
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.BmwDriveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Settings persist in DataStore; theme/unit changes flow back through the repository.
            val repo = remember { SettingsRepository(applicationContext) }
            val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val scope = rememberCoroutineScope()
            // Live link published by the Connect screen; real clients are used only when demo mode is off.
            val transport by ConnectionRegistry.transport.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val live = !settings.demoMode && transport != null
            val udsClient: UdsClient = remember(transport, settings.demoMode) {
                transport?.takeIf { !settings.demoMode }?.let { RealUdsClient(it) } ?: FakeUdsClient()
            }
            val telemetry: TelemetrySource = remember(udsClient) {
                if (live) UdsTelemetrySource(udsClient, imu = SensorImu(context)) else DemoTelemetrySource()
            }
            val ecuBook = if (live) EcuAddressBook.all else DemoEcuBook
            BmwDriveTheme(theme = settings.theme) {
                BmwDriveRoot(
                    theme = settings.theme,
                    onThemeChange = { scope.launch { repo.setTheme(it) } },
                    settings = settings,
                    repository = repo,
                    telemetry = telemetry,
                    udsClient = udsClient,
                    ecuBook = ecuBook,
                )
            }
        }
    }
}

@Composable
private fun BmwDriveRoot(
    theme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    settings: AppSettings,
    repository: SettingsRepository,
    telemetry: TelemetrySource,
    udsClient: UdsClient,
    ecuBook: List<EcuAddress>,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.bottomBar.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route ||
                            (dest == Dest.DASHBOARDS && current?.startsWith("dash/") == true),
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
            composable(Dest.DASHBOARDS.route) {
                DashboardsScreen(theme = theme, onOpen = { nav.navigate(it) })
            }
            val back: () -> Unit = { nav.popBackStack() }
            composable(Dashboards.REDLINE) { RedlineScreen(theme, telemetry, back, settings.speedUnit) }
            composable(Dashboards.GFORCE) { GForceScreen(theme, telemetry, back) }
            composable(Dashboards.DRAG) { DragScreen(theme, telemetry, back, settings.speedUnit) }
            composable(Dashboards.DYNO) { DynoScreen(theme, telemetry, back) }
            composable(Dashboards.WARMUP) { WarmUpScreen(theme, telemetry, back) }
            composable(Dest.FAULTS.route) { FaultScannerScreen(udsClient, ecuBook) }
            composable(Dest.DEPLOY.route) { DeployScreen() }
            composable(Dest.SETTINGS.route) {
                SettingsScreen(theme = theme, onThemeChange = onThemeChange, repository = repository)
            }
        }
    }
}
