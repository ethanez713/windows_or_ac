package com.acwindows

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Builds the exact [RemoteViews] each provider hands the launcher and runs [RemoteViews.apply]
 * on the real framework — the same inflation a home screen does. An invalid layout class or a
 * non-remotable setter throws HERE instead of silently killing the widget on the home screen,
 * which is the failure a clean build and `logcat -b crash` both miss.
 *
 * Each inflated widget is also drawn to a PNG under the app's external files dir, so
 * `widget-check.sh` can pull them for an optional glance — but the pass/fail is automatic
 * (inflates without throwing, and isn't blank).
 */
@RunWith(AndroidJUnit4::class)
class WidgetInflationTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val density = ctx.resources.displayMetrics.density

    @Test
    fun forecastWidgetInflates() {
        WidgetCache.save(ctx, sampleSnapshot())
        val cases = listOf(
            Triple(true, true, "forecast_score_daynight"),
            Triple(false, true, "forecast_plain_daynight"),
            Triple(true, false, "forecast_score_flat"),
        )
        cases.forEach { (score, dayNight, name) ->
            val id = 90_000 + (name.hashCode() and 0x7fff)
            ForecastWidgetConfig.save(ctx, id, ForecastWidgetConfig(showScore = score, showDayNight = dayNight))
            try {
                inflateAndAssert(ForecastWidgetProvider.buildViews(ctx, id, false, 392 to 186), name, 392, 186)
            } finally {
                ForecastWidgetConfig.delete(ctx, id)
            }
        }
    }

    @Test
    fun scoreWidgetInflates() {
        inflateAndAssert(AcWindowsProvider.buildViews(ctx, sampleSnapshot(), false, 250 to 180), "score_widget", 250, 180)
    }

    private fun inflateAndAssert(rv: RemoteViews, name: String, wDp: Int, hDp: Int) {
        val wPx = (wDp * density).toInt()
        val hPx = (hDp * density).toInt()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        // apply() + inflate + measure/layout/draw run on the main thread (they need a Looper);
        // a throw there is captured and rethrown on the test thread so the case actually fails.
        var error: Throwable? = null
        instr.runOnMainSync {
            try {
                val view: View = rv.apply(ctx, FrameLayout(ctx))
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, wPx, hPx)
                view.draw(Canvas(bmp))
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw AssertionError("$name: RemoteViews.apply/inflate failed", it) }
        dumpPng(bmp, name)
        assertTrue("$name: widget inflated but drew nothing (blank)", distinctColors(bmp) >= 4)
    }

    // Internal filesDir, not external: it always exists and is writable, and a debug build's
    // files are pullable with `run-as` (see widget-check.sh) — external storage didn't
    // materialise on the headless emulator.
    private fun dumpPng(bmp: Bitmap, name: String) {
        val dir = File(ctx.filesDir, "widget-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
