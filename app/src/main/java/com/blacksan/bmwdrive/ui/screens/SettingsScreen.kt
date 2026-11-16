package com.blacksan.bmwdrive.ui.screens

import androidx.compose.runtime.Composable
import com.blacksan.bmwdrive.ui.theme.AppTheme

// TODO(ui-agent): theme picker (4 themes), units (km/mph), DoIP config, demo-mode toggle.
@Composable
fun SettingsScreen(theme: AppTheme, onThemeChange: (AppTheme) -> Unit) =
    Placeholder("Settings", "Theme, units, and DoIP config go here.")
