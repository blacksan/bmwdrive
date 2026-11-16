package com.blacksan.bmwdrive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The four BMWDrive themes, mirroring the idrive7-apps app themes:
 * M Red, M Tricolour, Ice White, Night Amber.
 *
 * Each theme maps onto a Material 3 [androidx.compose.material3.ColorScheme].
 * Track/gauge screens read accent colours from [AppTheme.accents] so the
 * dashboards stay on-brand regardless of the active Material scheme.
 */
enum class AppTheme(val label: String) {
    M_RED("M Red"),
    M_TRICOLOUR("M Tricolour"),
    ICE_WHITE("Ice White"),
    NIGHT_AMBER("Night Amber");

    companion object {
        val DEFAULT = M_RED
    }
}

/** Accent palette consumed by the Canvas-drawn gauges (tach LEDs, g-circle, traces). */
data class GaugeAccents(
    val primary: Color,
    val warn: Color,
    val danger: Color,
    val track: Color,
    val good: Color,
)

val AppTheme.accents: GaugeAccents
    get() = when (this) {
        AppTheme.M_RED -> GaugeAccents(
            primary = Color(0xFFE2001A), warn = Color(0xFFFFB300),
            danger = Color(0xFFFF1744), track = Color(0xFF2A2A2E), good = Color(0xFF00E676),
        )
        AppTheme.M_TRICOLOUR -> GaugeAccents(
            primary = Color(0xFF1C69D4), warn = Color(0xFF6E6E70),
            danger = Color(0xFFE2001A), track = Color(0xFF26262A), good = Color(0xFF00C2A8),
        )
        AppTheme.ICE_WHITE -> GaugeAccents(
            primary = Color(0xFF0A84FF), warn = Color(0xFFFF9F0A),
            danger = Color(0xFFFF375F), track = Color(0xFFD4D7DD), good = Color(0xFF30D158),
        )
        AppTheme.NIGHT_AMBER -> GaugeAccents(
            primary = Color(0xFFFFB000), warn = Color(0xFFFF7A00),
            danger = Color(0xFFFF3B30), track = Color(0xFF1E1A12), good = Color(0xFFB8E986),
        )
    }

private fun schemeFor(theme: AppTheme, dark: Boolean) = when (theme) {
    AppTheme.ICE_WHITE -> if (dark) darkColorScheme(
        primary = Color(0xFF0A84FF), secondary = Color(0xFF64D2FF), background = Color(0xFF101217),
    ) else lightColorScheme(
        primary = Color(0xFF0A84FF), secondary = Color(0xFF0071E3), background = Color(0xFFF5F6F8),
    )

    AppTheme.NIGHT_AMBER -> darkColorScheme(
        primary = Color(0xFFFFB000), secondary = Color(0xFFFF7A00), background = Color(0xFF0C0A06),
        surface = Color(0xFF15120B),
    )

    AppTheme.M_TRICOLOUR -> darkColorScheme(
        primary = Color(0xFF1C69D4), secondary = Color(0xFFE2001A), background = Color(0xFF0B0D10),
    )

    AppTheme.M_RED -> darkColorScheme(
        primary = Color(0xFFE2001A), secondary = Color(0xFFFFB300), background = Color(0xFF0A0A0B),
        surface = Color(0xFF141416),
    )
}

@Composable
fun BmwDriveTheme(
    theme: AppTheme = AppTheme.DEFAULT,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = schemeFor(theme, dark),
        typography = Typography(),
        content = content,
    )
}
