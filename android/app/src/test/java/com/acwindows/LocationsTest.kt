package com.acwindows

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationsTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = ctx.getSharedPreferences("ac_windows", Context.MODE_PRIVATE)

    private fun legacy(lat: String, lon: String, place: String, follow: Boolean) {
        prefs.edit().putString("lat", lat).putString("lon", lon).putString("placeName", place)
            .putBoolean("useDeviceLocation", follow).putString("snapshot", "{\"cached\":1}").apply()
    }

    @Test
    fun freshInstallHoldsOnlyCurrentLocation() {
        val list = Locations.load(ctx)
        assertEquals(1, list.size)
        assertTrue(list[0].isCurrent)
        assertEquals(Locations.CURRENT_ID, Locations.selected(ctx).id)
        assertEquals("Current location", list[0].label)
    }

    @Test
    fun pinnedPlaceMigratesToSelectedFixedEntryAndItsSnapshotMoves() {
        legacy("41.9", "12.5", "Rome, Lazio", follow = false)
        val list = Locations.load(ctx)
        assertEquals(2, list.size)
        val fixed = list.single { !it.isCurrent }
        assertEquals(41.9, fixed.lat, 0.0)
        assertEquals("Rome, Lazio", fixed.label)
        assertEquals(fixed.id, Locations.selected(ctx).id)
        // The current entry's fallback fix is the old saved pair too.
        assertEquals(41.9, list.single { it.isCurrent }.lat, 0.0)
        assertFalse(prefs.contains("lat") || prefs.contains("useDeviceLocation") || prefs.contains("snapshot"))
        assertEquals("{\"cached\":1}", prefs.getString(WidgetCache.keyFor(fixed.id), null))
    }

    @Test
    fun followingThePhoneMigratesToCurrentOnly() {
        legacy("41.9", "12.5", "Rome, Lazio", follow = true)
        val list = Locations.load(ctx)
        assertEquals(1, list.size)
        assertEquals("Rome, Lazio", list[0].placeName)
        assertEquals(Locations.CURRENT_ID, Locations.selected(ctx).id)
        assertEquals("{\"cached\":1}", prefs.getString(WidgetCache.keyFor(Locations.CURRENT_ID), null))
    }

    @Test
    fun addStopsAtTheCap() {
        repeat(Locations.MAX - 1) { assertNotNull(Locations.add(ctx, it.toDouble(), 0.0, "P$it")) }
        assertEquals(Locations.MAX, Locations.load(ctx).size)
        assertNull(Locations.add(ctx, 50.0, 0.0, "one too many"))
        assertEquals(Locations.MAX, Locations.load(ctx).size)
    }

    @Test
    fun currentCannotBeRemovedAndRemovingTheSelectionFallsBackToIt() {
        assertFalse(Locations.remove(ctx, Locations.CURRENT_ID))
        val a = Locations.add(ctx, 1.0, 2.0, "A")!!
        Locations.select(ctx, a.id)
        prefs.edit().putString(WidgetCache.keyFor(a.id), "{}").apply()
        assertTrue(Locations.remove(ctx, a.id))
        assertEquals(Locations.CURRENT_ID, Locations.selected(ctx).id)
        assertFalse(prefs.contains(WidgetCache.keyFor(a.id)))
    }

    @Test
    fun unknownIdReadsAsCurrent() {
        assertTrue(Locations.byId(ctx, "gone").isCurrent)
        assertTrue(Locations.byId(ctx, null).isCurrent)
    }

    @Test
    fun nicknameIsTrimmedCappedAndBlankClears() {
        val a = Locations.add(ctx, 1.0, 2.0, "Springfield, IL")!!
        Locations.rename(ctx, a.id, "  " + "x".repeat(40) + "  ")
        assertEquals(Locations.NICKNAME_MAX, Locations.byId(ctx, a.id).nickname!!.length)
        Locations.rename(ctx, a.id, "   ")
        assertNull(Locations.byId(ctx, a.id).nickname)
        assertEquals("Springfield, IL", Locations.byId(ctx, a.id).label)
        Locations.rename(ctx, Locations.CURRENT_ID, "Here")
        assertEquals("Here", Locations.byId(ctx, Locations.CURRENT_ID).label)
    }

    @Test
    fun moveReordersAndCurrentCanLeaveTheTop() {
        val a = Locations.add(ctx, 1.0, 2.0, "A")!!
        val b = Locations.add(ctx, 3.0, 4.0, "B")!!
        Locations.move(ctx, 0, 2)
        assertEquals(listOf(a.id, b.id, Locations.CURRENT_ID), Locations.load(ctx).map { it.id })
        Locations.move(ctx, 2, 5)   // out of range is ignored
        assertEquals(listOf(a.id, b.id, Locations.CURRENT_ID), Locations.load(ctx).map { it.id })
    }

    @Test
    fun corruptListRecoversToCurrentOnly() {
        prefs.edit().putString("locations", "not json").apply()
        val list = Locations.load(ctx)
        assertEquals(1, list.size)
        assertTrue(list[0].isCurrent)
    }
}
