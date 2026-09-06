package com.acwindows

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/**
 * The three type families of the design system (docs/ux-redesign.md §0.4).
 *
 * The split is a rule, not a preference: [Heading] is for headings only, [Ui] for labels and
 * row text at 12sp and up, and [Mono] for *every* number, time and axis label. Mono is the
 * only family allowed below 12sp — the 9sp annotation and 11sp micro steps exist solely for
 * chart furniture, and a sans label at those sizes is unreadable on a widget.
 */
val Heading = FontFamily(
    Font(R.font.piazzolla_regular, FontWeight.Normal),
    Font(R.font.piazzolla_semibold, FontWeight.SemiBold),
)

val Ui = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)

/**
 * The same families as [android.graphics.Typeface], for the widget canvases — RemoteViews
 * render through a Bitmap, so widget text is drawn with Paint rather than composed.
 * Loading a font resource costs a resource lookup and a parse, so each is fetched once.
 */
object WidgetType {
    private val cache = HashMap<Int, Typeface>()

    private fun of(ctx: Context, resId: Int): Typeface = synchronized(cache) {
        cache.getOrPut(resId) {
            ResourcesCompat.getFont(ctx, resId) ?: Typeface.DEFAULT
        }
    }

    fun heading(ctx: Context): Typeface = of(ctx, R.font.piazzolla_semibold)
    fun ui(ctx: Context): Typeface = of(ctx, R.font.instrument_sans_regular)
    fun uiBold(ctx: Context): Typeface = of(ctx, R.font.instrument_sans_semibold)
    fun mono(ctx: Context): Typeface = of(ctx, R.font.plex_mono_regular)
    fun monoBold(ctx: Context): Typeface = of(ctx, R.font.plex_mono_semibold)
}
