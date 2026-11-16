package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.accents

private const val G_RANGE = 1.5f

/** Friction circle plotting (accelLat, accelLong) with a fading trail and peak-g readout. */
@Composable
fun GForceScreen(theme: AppTheme, source: TelemetrySource, onBack: (() -> Unit)? = null) {
    TelemetryEffect(source)
    val s by source.state.collectAsStateWithLifecycle()
    val accents = theme.accents
    var trail by remember { mutableStateOf(emptyList<GPoint>()) }
    var peak by remember { mutableStateOf<GPoint?>(null) }

    LaunchedEffect(s.timestampMs) {
        val lat = s.accelLat
        val long = s.accelLong
        if (lat != null && long != null) {
            val p = GPoint(lat, long, s.timestampMs)
            trail = trail.pushed(p)
            if (peak == null || gMagnitude(p.lat, p.long) > gMagnitude(peak!!.lat, peak!!.long)) peak = p
        }
    }
    val now = trail.lastOrNull()

    DashPage("G-Force", onBack) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radiusPx = minOf(size.width, size.height) / 2f - 8.dp.toPx()
                val c = Offset(size.width / 2f, size.height / 2f)
                fun at(lat: Float, long: Float) = Offset(
                    c.x + lat / G_RANGE * radiusPx,
                    c.y - long / G_RANGE * radiusPx, // accelerating plots upward
                )
                // Rings at 0.5 / 1.0 / 1.5 g and crosshair.
                for (g in listOf(0.5f, 1.0f, 1.5f)) {
                    drawCircle(accents.track, g / G_RANGE * radiusPx, c, style = Stroke(2.dp.toPx()))
                }
                drawLine(accents.track, Offset(c.x - radiusPx, c.y), Offset(c.x + radiusPx, c.y), 2.dp.toPx())
                drawLine(accents.track, Offset(c.x, c.y - radiusPx), Offset(c.x, c.y + radiusPx), 2.dp.toPx())
                // Fading trail.
                trail.forEachIndexed { i, p ->
                    drawCircle(
                        accents.primary.copy(alpha = trailAlpha(i, trail.size)),
                        radius = 4.dp.toPx(), center = at(p.lat, p.long),
                    )
                }
                // Peak marker and live dot.
                peak?.let {
                    drawCircle(accents.warn, 8.dp.toPx(), at(it.lat, it.long), style = Stroke(3.dp.toPx()))
                }
                now?.let { drawCircle(accents.danger, 12.dp.toPx(), at(it.lat, it.long)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Readout("LAT", fmt(s.accelLat, 2, " g"))
            Readout("LONG", fmt(s.accelLong, 2, " g"))
            Readout("PEAK", peak?.let { fmt(gMagnitude(it.lat, it.long), 2, " g") } ?: "—")
        }
        Button(
            onClick = { peak = null; trail = emptyList() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Reset peak") }
    }
}

@Composable
private fun Readout(label: String, value: String) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}
