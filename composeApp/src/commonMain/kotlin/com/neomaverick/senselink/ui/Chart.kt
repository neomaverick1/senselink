package com.neomaverick.senselink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.neomaverick.senselink.ble.Reading

/** Live line chart with a gradient fill and optional alert limits (dashed). */
@Composable
fun LiveChart(points: List<Reading>, color: Color, limits: List<Double> = emptyList(), modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.value }
        val lo0 = (values + limits).min()
        val hi0 = (values + limits).max()
        val pad = ((hi0 - lo0) * 0.15).coerceAtLeast(1.0)
        val lo = lo0 - pad
        val hi = hi0 + pad
        val t0 = points.first().atMillis
        val span = (points.last().atMillis - t0).coerceAtLeast(1)
        fun x(r: Reading) = (r.atMillis - t0).toFloat() / span * size.width
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()

        // Grid
        for (i in 1..3) {
            val gy = size.height * i / 4
            drawLine(Color(0x14000000), Offset(0f, gy), Offset(size.width, gy), 1f)
        }
        limits.forEach {
            drawLine(
                Coral.copy(alpha = 0.7f),
                Offset(0f, y(it)),
                Offset(size.width, y(it)),
                2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            )
        }
        val line = Path()
        points.forEachIndexed { i, r -> if (i == 0) line.moveTo(x(r), y(r.value)) else line.lineTo(x(r), y(r.value)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(points.last()), size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(Color.White, 11f, Offset(x(last), y(last.value)))
        drawCircle(color, 7f, Offset(x(last), y(last.value)))
    }
}
