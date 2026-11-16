package com.blacksan.bmwdrive.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacksan.bmwdrive.settings.SpeedUnit
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.GaugeAccents
import com.blacksan.bmwdrive.ui.theme.accents
import kotlin.math.cos
import kotlin.math.sin

private const val TACH_MAX = 8000
private const val REDLINE = 6500
private const val SHIFT_RPM = 6200
private const val LED_START = 3500
private const val SWEEP = 240f
private const val START_ANGLE = 150f

/** Large tach arc, 12-LED shift bar, gear digit and upshift cue. */
@Composable
fun RedlineScreen(
    theme: AppTheme,
    source: TelemetrySource,
    onBack: (() -> Unit)? = null,
    speedUnit: SpeedUnit = SpeedUnit.KMH,
) {
    TelemetryEffect(source)
    val s by source.state.collectAsStateWithLifecycle()
    val accents = theme.accents
    val leds = shiftLedStates(s.rpm, LED_START, REDLINE)
    val shift = shouldUpshift(s.rpm, SHIFT_RPM)
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(150, easing = LinearEasing), RepeatMode.Reverse),
        label = "blinkAlpha",
    )

    DashPage("Redline", onBack) {
        ShiftLedBar(leds, accents, flash = if (shift) blink else 1f)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            TachArc(s.rpm, accents)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (s.gear == null) "—" else if (s.gear == 0) "N" else s.gear.toString(),
                    fontSize = 120.sp, fontWeight = FontWeight.Black,
                    color = if (shift) accents.danger else MaterialTheme.colorScheme.onSurface,
                )
                Text(fmt(s.rpm), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("rpm", style = MaterialTheme.typography.labelLarge)
                Text(
                    fmt(s.speedKmh?.let { speedUnit.fromKmh(it) }, 0, " ${speedUnit.label}"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        Text(
            if (shift) "UPSHIFT" else " ",
            modifier = Modifier.fillMaxWidth(),
            color = accents.danger.copy(alpha = if (shift) blink else 1f),
            fontSize = 36.sp, fontWeight = FontWeight.Black,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun ledColor(level: LedLevel, a: GaugeAccents): Color = when (level) {
    LedLevel.OFF -> a.track
    LedLevel.GREEN -> a.good
    LedLevel.AMBER -> a.warn
    LedLevel.RED -> a.danger
}

@Composable
private fun ShiftLedBar(leds: List<LedLevel>, accents: GaugeAccents, flash: Float) {
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (leds.size - 1)) / leds.size
        leds.forEachIndexed { i, level ->
            val c = ledColor(level, accents)
            drawRoundRect(
                color = if (level == LedLevel.OFF) c else c.copy(alpha = flash),
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        }
    }
}

@Composable
private fun TachArc(rpm: Int?, accents: GaugeAccents) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 28.dp.toPx()
        val d = minOf(size.width, size.height) - stroke
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        // Track, with the redline zone painted in danger colour.
        drawArc(accents.track, START_ANGLE, SWEEP, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        val redStart = fractionOf(REDLINE.toFloat(), 0f, TACH_MAX.toFloat())
        drawArc(
            accents.danger.copy(alpha = 0.35f), START_ANGLE + SWEEP * redStart, SWEEP * (1 - redStart),
            false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt),
        )
        if (rpm != null) {
            val f = fractionOf(rpm.toFloat(), 0f, TACH_MAX.toFloat())
            val color = when {
                rpm >= REDLINE -> accents.danger
                rpm >= SHIFT_RPM -> accents.warn
                else -> accents.primary
            }
            drawArc(color, START_ANGLE, SWEEP * f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // Tick marks every 1000 rpm.
        val r = d / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        for (k in 0..TACH_MAX / 1000) {
            val ang = Math.toRadians((START_ANGLE + SWEEP * k * 1000f / TACH_MAX).toDouble())
            val inner = r - stroke * 0.9f
            val outer = r - stroke * 1.5f
            drawLine(
                accents.warn.copy(alpha = 0.6f),
                center + Offset((cos(ang) * inner).toFloat(), (sin(ang) * inner).toFloat()),
                center + Offset((cos(ang) * outer).toFloat(), (sin(ang) * outer).toFloat()),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}
