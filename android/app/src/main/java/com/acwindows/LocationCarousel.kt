package com.acwindows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The bottom location selector: flat pills after the design system's touch
 * `SegmentedControl` (30dp pills, 6dp gap, 44dp touch rows, selected one filled with the
 * orange accent, the rest warm-grey controls on the card surface). Current location is the
 * bare pin glyph, the platform idiom for "where I am".
 *
 * When every pill fits across the bar the row is static and centred. When they overflow it
 * rotates: the list repeats endlessly in both directions, a fling settles with the nearest pill
 * centred, and that pill becomes the selection. Selection is committed on settle rather than
 * per scrolled pixel, so a fling across several places starts one fetch, not one per place.
 */
@Composable
fun LocationCarousel(
    locations: List<SavedLocation>,
    selectedId: String,
    locationMode: LocationProvider.LocationMode,
    onSelect: (String) -> Unit,
) {
    val chrome = Skin.chrome
    Column(Modifier.fillMaxWidth().background(chrome.card).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(chrome.controlBorder))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val widths = pillWidths(locations, locationMode)
            val fits = widths.fold(0.dp) { a, w -> a + w } + PILL_GAP * (locations.size - 1) + EDGE * 2 <= maxWidth
            if (fits) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PILL_GAP, Alignment.CenterHorizontally),
                ) {
                    locations.forEach { loc ->
                        Pill(loc, loc.id == selectedId, locationMode) { onSelect(loc.id) }
                    }
                }
            } else {
                RotatingPills(locations, selectedId, locationMode, onSelect)
            }
        }
    }
}

@Composable
private fun RotatingPills(
    locations: List<SavedLocation>,
    selectedId: String,
    locationMode: LocationProvider.LocationMode,
    onSelect: (String) -> Unit,
) {
    val n = locations.size
    val base = n * (LAPS / 2)   // a lap boundary far from both ends, so either direction has room
    val selectedIdx = locations.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = base + selectedIdx)
    val scope = rememberCoroutineScope()
    val currentLocations by rememberUpdatedState(locations)
    val currentSelected by rememberUpdatedState(selectedId)
    val select by rememberUpdatedState(onSelect)

    // Bring the selection to the centre whenever it changes from outside (a swipe on the
    // forecast, a reload after the Locations screen): jump on first layout, glide after.
    // Picks the copy nearest what is already on screen, so a swipe past the end wraps.
    val placed = remember { mutableStateOf(false) }
    LaunchedEffect(selectedId, n) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        if (centredIndex(state)?.mod(n) != selectedIdx) {
            centre(state, nearestCopy(state, n, selectedIdx), animate = placed.value)
        }
        placed.value = true
    }

    // Settle → centre the nearest pill exactly and select it.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .drop(1)
            .filter { !it }
            .collect {
                val v = centredIndex(state) ?: return@collect
                centre(state, v, animate = true)
                val loc = currentLocations[v.mod(currentLocations.size)]
                if (loc.id != currentSelected) select(loc.id)
            }
    }

    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PILL_GAP),
    ) {
        items(count = n * LAPS, key = { it }) { v ->
            val loc = locations[v % n]
            // A tap only scrolls; the settle that follows makes the selection, as a fling's does.
            Pill(loc, loc.id == selectedId, locationMode) {
                scope.launch { centre(state, v, animate = true) }
            }
        }
    }
}

/** The virtual index of [idx]'s copy closest to the item currently centred. */
private fun nearestCopy(state: LazyListState, n: Int, idx: Int): Int {
    val from = centredIndex(state) ?: state.firstVisibleItemIndex
    val lap = from - from.mod(n)
    return listOf(lap - n + idx, lap + idx, lap + n + idx).minBy { abs(it - from) }
}

private fun centredIndex(state: LazyListState): Int? {
    val info = state.layoutInfo
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index
}

private suspend fun centre(state: LazyListState, index: Int, animate: Boolean) {
    fun delta(): Float? {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return (item.offset + item.size / 2 - mid).toFloat()
    }
    if (delta() == null) {
        state.scrollToItem(index)
        withFrameNanos { }   // let the jump lay out before measuring where the item landed
    }
    val d = delta() ?: return
    if (abs(d) < 1f) return
    if (animate) state.animateScrollBy(d, tween(DUR_MS, easing = EASE_SNAP)) else state.scrollBy(d)
}

@Composable
private fun Pill(
    loc: SavedLocation,
    selected: Boolean,
    locationMode: LocationProvider.LocationMode,
    onClick: () -> Unit,
) {
    val chrome = Skin.chrome
    val fg = if (selected) chrome.onAccent else chrome.controlText
    val shape = RoundedCornerShape(50)
    // The 44dp touch row around the 30dp pill.
    Box(
        Modifier.height(TOUCH_H).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(PILL_H).widthIn(max = PILL_MAX_W).clip(shape)
                .background(if (selected) chrome.accent else chrome.control)
                .border(1.dp, if (selected) chrome.accent else chrome.controlBorder, shape)
                .padding(horizontal = if (loc.isCurrent) PIN_PAD else PILL_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loc.isCurrent) {
                Icon(
                    painterResource(locationMode.iconRes), contentDescription = loc.label,
                    tint = fg, modifier = Modifier.size(PIN),
                )
            } else {
                Text(
                    loc.label, style = pillText, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Each pill's laid-out width, measured the way [Pill] draws it, to decide whether all fit. */
@Composable
private fun pillWidths(
    locations: List<SavedLocation>,
    locationMode: LocationProvider.LocationMode,
): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(locations, locationMode, density) {
        locations.map { loc ->
            if (loc.isCurrent) return@map PIN + PIN_PAD * 2
            val text = with(density) { measurer.measure(loc.label, pillText).size.width.toDp() }
            (text + PILL_PAD * 2).coerceAtMost(PILL_MAX_W)
        }
    }
}

private val pillText = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
private val PILL_H = 30.dp
private val TOUCH_H = 44.dp
private val PILL_PAD = 12.dp
private val PILL_GAP = 6.dp
private val PILL_MAX_W = 160.dp
private val EDGE = 12.dp
private val PIN = 16.dp
private val PIN_PAD = 10.dp

/** Copies of the list laid end to end; far more than any fling can cross. */
private const val LAPS = 1000

/** Design-system `--dur-3` with `--ease-snap`. */
private const val DUR_MS = 200
private val EASE_SNAP = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)
