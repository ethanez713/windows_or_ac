package com.acwindows

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Score bars for one day. Bars use the full canvas height (the open-window flags now
 * live in their own strip ABOVE — see [OpenWindowFlags] — so they no longer steal bar
 * height).
 */
@Composable
internal fun DaySparkline(
    points: List<ForecastPoint>,
    threshold: Double,
    comfort: Comfort,
    nowIdx: Int,
    selectedIdx: Int?,
    modifier: Modifier,
) {
    val d = Skin.data
    val open = d.score
    val closed = d.blockedBase
    val selColor = Skin.chrome.heading
    val coldArgb = coldMarkArgb(Skin.chrome.dark)
    Canvas(modifier) {
        val n = points.size.coerceAtLeast(1)
        val bw = size.width / n
        val plotBot = size.height
        val plotH = plotBot

        // Same midpoint the temperature line uses for the segment that leaves this hour
        // (the segment that arrives, for the last hour), so a red or white bar is a red or white line.
        fun lineSample(i: Int): Double? {
            val a = points[i].tempF ?: return null
            val b = points.getOrNull(i + 1)?.tempF ?: points.getOrNull(i - 1)?.tempF
            return if (b == null) a else (a + b) / 2.0
        }

        fun tooCold(p: ForecastPoint): Boolean =
            p.breakdown?.limiting() == Factor.TEMP && isTooCold(p.tempF, comfort)

        fun penalized(i: Int, p: ForecastPoint): Color {
            val factor = p.breakdown?.limiting()
            val sample = lineSample(i)
            // Cold is ice blue whatever the line shows. Otherwise, when temperature is what
            // holds the score down, the bar uses the line color; another factor keeps its own
            // hue even if the line is red or white.
            return when {
                tooCold(p) -> Color(d.raw.coldBar)
                factor == Factor.TEMP && sample != null ->
                    Color(tempLineArgb(sample, comfort, d.raw.temp, coldArgb))
                factor != null -> Color(Tokens.blockedBar(d.raw, Tokens.barHue(d.raw, factor)))
                else -> closed
            }
        }

        points.forEachIndexed { i, p ->
            val h = (p.score / 100.0).toFloat() * plotH
            val blocked = p.score < threshold
            val barColor = if (blocked) penalized(i, p) else open
            val left = i * bw
            val w = bw * 0.7f
            drawRect(color = barColor, topLeft = Offset(left, plotBot - h), size = Size(w, h))
            // Cold and rain bars carry a pattern too, so they don't rest on telling two blues
            // apart: snowflakes on the ice, drops on the navy.
            if (blocked && h > 0f) {
                when {
                    tooCold(p) -> snowflakes(left, plotBot - h, w, plotBot)
                    p.breakdown?.limiting() == Factor.RAIN -> raindrops(left, plotBot - h, w, plotBot, Color(d.raw.rainDrop))
                }
            }
        }

        // A zero-height bar still needs a mark, in the same color as the bar would be.
        val markH = 2.5f.dp.toPx()
        points.forEachIndexed { i, p ->
            if (p.score > 0.5 || p.breakdown == null) return@forEachIndexed
            drawRect(penalized(i, p), topLeft = Offset(i * bw, plotBot - markH), size = Size(bw * 0.7f, markH))
        }

        // Static "now" marker (today only — nowIdx is -1 on other days), centred on the bar.
        if (nowIdx in 0 until n) nowMarker(nowIdx * bw + bw * 0.35f, 0f, plotBot, d.now)

        // The hour picked in the expanded charts below, as a solid line through its bar, so
        // the selected score is easy to read off these bars.
        if (selectedIdx in 0 until n) {
            val x = selectedIdx!! * bw + bw * 0.35f
            drawLine(selColor, Offset(x, 0f), Offset(x, plotBot), strokeWidth = 1.5f.dp.toPx())
        }
    }
}

/** White six-point snowflakes stacked up a cold score bar from its foot, clipped to the bar. */
private fun DrawScope.snowflakes(left: Float, top: Float, w: Float, bottom: Float) {
    val r = minOf(1.6.dp.toPx(), w * 0.42f)
    val step = r * 2.9f
    val halo = Color(0x803E7FA8)
    clipRect(left, top, left + w, bottom) {
        val cx = left + w / 2
        var cy = bottom - step / 2
        while (cy > top - r) {
            // A thin blue halo under each stroke keeps the white legible on the pale ice.
            for ((color, rr, sw) in listOf(Triple(halo, r * 1.08f, r * 0.55f), Triple(Color.White, r, r * 0.34f))) {
                for (deg in intArrayOf(90, 30, -30)) {
                    val a = Math.toRadians(deg.toDouble())
                    val dx = (rr * cos(a)).toFloat()
                    val dy = (rr * sin(a)).toFloat()
                    drawLine(color, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), strokeWidth = sw)
                }
            }
            cy -= step
        }
    }
}

/** Small teardrops, point up, staggered left and right up a rain score bar, clipped to the bar. */
private fun DrawScope.raindrops(left: Float, top: Float, w: Float, bottom: Float, color: Color) {
    val r = minOf(0.72.dp.toPx(), w * 0.16f)
    val step = r * 4f
    val off = w * 0.22f
    val a = Math.toRadians(38.0)
    val sx = (r * cos(a)).toFloat()
    val sy = (r * sin(a) * 0.9).toFloat()
    clipRect(left, top, left + w, bottom) {
        val cx = left + w / 2
        var cy = bottom - step / 2 + r
        var k = 0
        while (cy > top - r) {
            val x = cx + if (k % 2 == 0) off else -off
            drawCircle(color, radius = r, center = Offset(x, cy))
            drawPath(
                Path().apply {
                    moveTo(x - sx, cy - sy)
                    lineTo(x, cy - r * 2.25f)
                    lineTo(x + sx, cy - sy)
                    close()
                },
                color,
            )
            cy -= step
            k++
        }
    }
}

/** Static dashed "now" marker at the current hour (today's graphs only). When [tm] is
 *  given, also draws a small "now" label clamped on-screen. */
private fun DrawScope.nowMarker(
    cx: Float, top: Float, bottom: Float, ink: Color, tm: TextMeasurer? = null,
) {
    drawLine(
        ink.copy(alpha = 0.85f), Offset(cx, top), Offset(cx, bottom),
        strokeWidth = 1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
    tm?.let {
        val l = it.measure("now", TextStyle(color = ink, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        val x = (cx + 2.dp.toPx()).coerceAtMost(size.width - l.size.width)
        drawText(l, topLeft = Offset(x.coerceAtLeast(0f), top))
    }
}
