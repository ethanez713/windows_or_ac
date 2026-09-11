package com.acwindows

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runs the two widget bitmap renderers on a real (native-graphics) Canvas with real resources,
 * so a render that throws or comes out blank fails `./gradlew test` in seconds — the crash
 * class of bug that plain JVM unit tests miss because `android.graphics` is stubbed there.
 *
 * This is the fast gate. It does NOT prove the RemoteViews inflate in a launcher's process —
 * that's [WidgetInflationTest], which runs the real RemoteViews.apply() on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetRenderTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val density = 2.75f

    @Test
    fun forecastPanelRenders() {
        val s = sampleSnapshot()
        val cfg = AppConfig.load(ctx)
        val wPx = (392 * density).toInt()
        val hPx = (186 * density).toInt()
        for (dark in booleanArrayOf(true, false)) {
            for (showScore in booleanArrayOf(true, false)) {
                val widget = ForecastWidgetConfig(showScore = showScore)
                val bmp = ForecastPanel.render(ctx, s, cfg, widget, TempUnit.F, wPx, hPx, density, dark)
                assertAlive(bmp, wPx, hPx, "forecast dark=$dark score=$showScore")
            }
        }
    }

    @Test
    fun scoreGraphRenders() {
        val s = sampleSnapshot()
        for (dark in booleanArrayOf(true, false)) {
            val bmp = Graph.render(
                scores = s.scores, times = s.times, worst = s.worst, isDay = s.isDay,
                precipProb = s.precipProb, precipIn = s.precipIn,
                bestStart = s.bestStart, bestEnd = s.bestEnd, threshold = 60.0,
                widthPx = 500, heightPx = 360, density = density,
                tempF = s.tempSeries, dewF = s.dewSeries, dark = dark,
            )
            assertAlive(bmp, 500, 360, "score dark=$dark")
        }
    }

    private fun assertAlive(bmp: Bitmap, w: Int, h: Int, label: String) {
        assertTrue("$label: wrong size ${bmp.width}x${bmp.height}", bmp.width == w && bmp.height == h)
        assertTrue("$label: blank bitmap (renderer drew nothing)", distinctColors(bmp) >= 4)
    }
}
