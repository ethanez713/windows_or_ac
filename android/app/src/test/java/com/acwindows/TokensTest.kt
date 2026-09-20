package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design system's own rule, asserted rather than eyeballed: every mark has to clear
 * 3:1 against the card it is drawn on, in both themes (docs/ux-redesign.md §0.1). This is
 * what stops a future palette edit from quietly reintroducing an unreadable series.
 */
class TokensTest {

    private fun marks(d: Tokens.Data) = mapOf(
        "temp" to d.temp, "dew" to d.dew, "aqi" to d.aqi, "rain" to d.rain,
        "score" to d.score, "now" to d.now, "storm" to d.storm, "blockedBase" to d.blockedBase,
        "sun" to d.sun, "cloud" to d.cloud,
    )

    private fun assertClearsFloor(theme: String, dark: Boolean) {
        val card = Tokens.chrome(dark).card
        marks(Tokens.data(dark)).forEach { (name, color) ->
            val ratio = Tokens.contrastRatio(color, card)
            assertTrue(
                "$theme $name is $ratio:1 on the card, below the ${Tokens.MIN_MARK_CONTRAST}:1 floor",
                ratio >= Tokens.MIN_MARK_CONTRAST,
            )
        }
    }

    @Test fun everyDataColourClearsTheFloorOnItsOwnCard() {
        assertClearsFloor("light", dark = false)
        assertClearsFloor("dark", dark = true)
    }

    @Test fun everyAqiPaletteClearsTheFloorAcrossItsRange() {
        // The published EPA green and yellow measure ~1:1 on off-white, which is the whole
        // reason the light ramp restates them — so the light steps are the ones to check.
        for (palette in AqiPalette.values()) {
            for (dark in listOf(false, true)) {
                val card = Tokens.chrome(dark).card
                for (aqi in listOf(0, 25, 50, 75, 100, 150, 200, 300, 400)) {
                    val ratio = Tokens.contrastRatio(AqiRamp.colorAt(aqi.toDouble(), palette, dark), card)
                    assertTrue(
                        "$palette ${if (dark) "dark" else "light"} at AQI $aqi is $ratio:1",
                        ratio >= Tokens.MIN_MARK_CONTRAST,
                    )
                }
            }
        }
    }

    @Test fun blockedBarStaysNearerTheNeutralThanTheFactorHue() {
        val d = Tokens.data(dark = true)
        val bar = Tokens.blockedBar(d, d.rain)
        // 0.38 of the way over: closer to the neutral base than to the factor's own hue,
        // so a blocked bar can never be mistaken for an open one.
        assertTrue(
            Tokens.contrastRatio(bar, d.blockedBase) < Tokens.contrastRatio(bar, d.score),
        )
    }

    @Test fun theRampIsAnchoredOnTheEpaBreakpoints() {
        // Interpolation must not drift the anchors themselves.
        assertEquals(
            AqiRamp.colorAt(0.0, AqiPalette.EPA, dark = true),
            AqiRamp.colorAt(-20.0, AqiPalette.EPA, dark = true),
        )
        assertEquals("good", AqiRamp.category(50.0))
        assertEquals("moderate", AqiRamp.category(51.0))
        assertEquals("hazardous", AqiRamp.category(301.0))
    }

    @Test fun sweepHoldsTheLastReadingAcrossAGap() {
        // A missing hour must not drop the strip to clean-air green, which would show air
        // quality that was never measured.
        val (colors, positions) = AqiRamp.sweep(listOf(140, null, null), AqiPalette.EPA, dark = true)
        assertEquals(AqiRamp.colorAt(140.0, AqiPalette.EPA, dark = true), colors[0])
        assertEquals(colors[0], colors[2])
        assertEquals(0f, positions.first(), 1e-6f)
        assertEquals(1f, positions.last(), 1e-6f)
    }
}
