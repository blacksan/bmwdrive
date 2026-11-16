package com.blacksan.bmwdrive.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacksan.bmwdrive.telemetry.TelemetrySource
import com.blacksan.bmwdrive.ui.theme.AppTheme
import com.blacksan.bmwdrive.ui.theme.accents

private const val ENGINE_REDLINE = 6500

/** Oil + coolant gauges, a safe rev limit that rises with oil temp, and a warm-up percentage. */
@Composable
fun WarmUpScreen(theme: AppTheme, source: TelemetrySource, onBack: (() -> Unit)? = null) {
    TelemetryEffect(source)
    val s by source.state.collectAsStateWithLifecycle()
    val accents = theme.accents
    val limit = safeRevLimit(s.oilC, ENGINE_REDLINE)
    val pct = warmUpPercent(s.oilC, s.coolantC)
    val overLimit = limit != null && (s.rpm ?: 0) > limit

    DashPage("Warm-Up", onBack) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TempGauge("OIL", s.oilC, 130f, accents.primary, accents.track, accents.danger, Modifier.weight(1f))
            TempGauge("COOLANT", s.coolantC, 130f, accents.warn, accents.track, accents.danger, Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Safe rev limit", style = MaterialTheme.typography.labelLarge)
            Text(
                fmt(limit, " rpm"), fontSize = 40.sp, fontWeight = FontWeight.Black,
                color = if (overLimit) accents.danger else accents.good,
            )
            if (overLimit) Text("Over limit - ease off", color = accents.danger, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Warm-up: ${fmt(pct, 0, "%")}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { (pct ?: 0f) / 100f },
                modifier = Modifier.fillMaxWidth().height(12.dp),
                color = accents.good, trackColor = accents.track,
            )
        }
    }
}

@Composable
private fun TempGauge(
    label: String, tempC: Float?, maxC: Float,
    color: Color, track: Color, hot: Color, modifier: Modifier,
) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 18.dp.toPx()
            val d = minOf(size.width, size.height) - stroke
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(track, 150f, 240f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            if (tempC != null) {
                val f = fractionOf(tempC, 0f, maxC)
                drawArc(if (tempC > 110f) hot else color, 150f, 240f * f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmt(tempC, 0, "°"), fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
