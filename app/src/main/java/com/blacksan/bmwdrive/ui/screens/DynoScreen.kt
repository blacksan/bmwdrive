package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.accents

private const val RPM_MAX = 7000f

/**
 * Estimated power/torque vs rpm. Model: P = (m*a + drag + rolling) * v, then derated
 * for drivetrain loss. Only full-throttle, accelerating samples are folded into the curve.
 */
@Composable
fun DynoScreen(theme: AppTheme, source: TelemetrySource, onBack: (() -> Unit)? = null) {
    TelemetryEffect(source)
    val accents = theme.accents
    var massKg by rememberSaveable { mutableStateOf(1650f) }
    var points by remember { mutableStateOf<Map<Int, DynoPoint>>(emptyMap()) }

    LaunchedEffect(source) {
        source.state.collect { s ->
            points = points.withSample(s.rpm, s.speedKmh, s.accelLong, s.throttlePct, DynoConfig(massKg = massKg))
        }
    }
    val curve = points.values.sortedBy { it.rpm }

    DashPage("Dyno (estimate)", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Legend("Power (kW)", accents.primary, curve.peakKw()?.let { "%.0f kW @ %d".format(it.kw, it.rpm) } ?: "—")
            Legend("Torque (Nm)", accents.warn, curve.peakNm()?.let { "%.0f Nm @ %d".format(it.nm, it.rpm) } ?: "—")
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            drawGrid(accents.track)
            val pMax = axisMax(curve.map { it.kw }, 100f)
            val tMax = axisMax(curve.map { it.nm }, 200f)
            drawSeries(curve.map { it.rpm.toFloat() / RPM_MAX to it.kw / pMax }, accents.primary)
            drawSeries(curve.map { it.rpm.toFloat() / RPM_MAX to it.nm / tMax }, accents.warn)
        }
        Column {
            Text("Vehicle mass: %.0f kg".format(massKg), style = MaterialTheme.typography.labelLarge)
            Slider(value = massKg, onValueChange = { massKg = it }, valueRange = 900f..2800f)
        }
        Button(
            onClick = { points = emptyMap() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Clear curve") }
    }
}

@Composable
private fun Legend(name: String, color: Color, value: String) {
    Column {
        Text(name, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

private fun DrawScope.drawGrid(color: Color) {
    for (i in 0..4) {
        val y = size.height * i / 4f
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
    for (i in 0..7) {
        val x = size.width * i / 7f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}

/** Draws normalized (x,y in 0..1, y up) points as a polyline. */
private fun DrawScope.drawSeries(pts: List<Pair<Float, Float>>, color: Color) {
    if (pts.isEmpty()) return
    val path = Path()
    pts.forEachIndexed { i, (x, y) ->
        val px = x.coerceIn(0f, 1f) * size.width
        val py = size.height - y.coerceIn(0f, 1f) * size.height
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, color, style = Stroke(3.dp.toPx()))
    pts.forEach { (x, y) ->
        drawCircle(color, 3.dp.toPx(), Offset(x.coerceIn(0f, 1f) * size.width, size.height - y.coerceIn(0f, 1f) * size.height))
    }
}
