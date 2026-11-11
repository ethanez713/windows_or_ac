package com.acwindows

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Open-window pills, left to right: the first row whose last pill ends at least the gap
 * before this one, up to three rows. A pill that fits none is dropped, and a pill centred
 * past either edge of the strip is clamped back inside it.
 */
class FlagRowsTest {

    @Test fun oneRowWhenPillsClearTheGap() {
        // 0..20 and 22..42. 22 is exactly the previous end plus the gap, so both stay on row 0.
        val placed = assignFlagRows(listOf(10f, 32f), listOf(20f, 20f), widthPx = 200f, gapPx = 2f)
        assertEquals(listOf(0f, 22f), placed.map { it.left })
        assertEquals(listOf(0, 0), placed.map { it.row })
    }

    @Test fun secondRowWhenTheGapIsMissed() {
        // The second pill starts at 21, one pixel inside the gap, so it takes row 1.
        val placed = assignFlagRows(listOf(10f, 31f), listOf(20f, 20f), widthPx = 200f, gapPx = 2f)
        assertEquals(listOf(0f, 21f), placed.map { it.left })
        assertEquals(listOf(0, 1), placed.map { it.row })
    }

    @Test fun threeClosePillsUseThreeRows() {
        // Given rightmost first. The leftmost still takes row 0, then row 1, then row 2.
        val placed = assignFlagRows(listOf(54f, 50f, 52f), listOf(30f, 30f, 30f), widthPx = 200f, gapPx = 2f)
        val rowOf = placed.associate { it.index to it.row }
        assertEquals(mapOf(1 to 0, 2 to 1, 0 to 2), rowOf)
    }

    @Test fun aFourthClosePillIsDropped() {
        val placed = assignFlagRows(
            listOf(50f, 52f, 54f, 56f),
            listOf(30f, 30f, 30f, 30f),
            widthPx = 200f,
            gapPx = 2f,
        )
        assertEquals(listOf(0, 1, 2), placed.map { it.index })
        assertEquals(listOf(0, 1, 2), placed.map { it.row })
    }

    @Test fun clampsAtBothEdges() {
        // Centre 0 would hang off the left; centre 200 would hang off the right.
        val placed = assignFlagRows(listOf(0f, 200f), listOf(20f, 20f), widthPx = 200f, gapPx = 2f)
        val byIndex = placed.associateBy { it.index }
        assertEquals(0f, byIndex.getValue(0).left, 0f)
        assertEquals(180f, byIndex.getValue(1).left, 0f)
        assertEquals(0, byIndex.getValue(0).row)
        assertEquals(0, byIndex.getValue(1).row)
    }
}
