package com.acwindows

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Settings → Locations: add a place by search, remove it, give it a nickname, and drag the
 * list into the order the app's bottom selector shows. Every change is saved as it is made.
 */
class LocationsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textScale = AppConfig.load(this).appTextScalePct / 100f
        setContent { AcWindowsTheme(textScale) { LocationsScreen(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(Locations.load(context)) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedLocation?>(null) }
    var locationMode by remember { mutableStateOf(LocationProvider.mode(context, true)) }
    val full = items.size >= Locations.MAX

    fun reload() { items = Locations.load(context) }

    fun add() {
        val q = query.trim()
        if (q.isEmpty() || busy || full) return
        busy = true
        status = "Looking up “$q”…"
        scope.launch {
            val hit = LocationProvider.resolve(context, q)
            status = when {
                hit == null -> "Couldn't find “$q” — try a city and state, a ZIP, or “lat, lon”."
                Locations.add(context, hit.lat, hit.lon, hit.label) == null -> "Up to ${Locations.MAX} locations."
                else -> { query = ""; "Added ${hit.label}" }
            }
            reload()
            busy = false
        }
    }

    fun delete(loc: SavedLocation) {
        val shown = ForecastWidgetProvider.boundLocations(context).any { it.id == loc.id }
        Locations.remove(context, loc.id)
        reload()
        // Its widgets fall back to Current location, which they now need fetched and drawn.
        if (shown) ForecastWidgetProvider.refreshNow(context)
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationMode = LocationProvider.mode(context, true)
        if (!LocationProvider.hasPermission(context)) return@rememberLauncherForActivityResult
        scope.launch {
            val fix = LocationProvider.currentLocation(context) ?: return@launch
            val lat = AppConfig.round4(fix.latitude)
            val lon = AppConfig.round4(fix.longitude)
            Locations.updateCurrentFix(context, lat, lon, LocationProvider.placeName(context, lat, lon))
            reload()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Locations", fontFamily = Heading, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (full) "Up to ${Locations.MAX} locations" else "Add a place") },
                placeholder = { Text("City, ZIP, or lat, lon") },
                singleLine = true,
                enabled = !full,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { add() }),
                trailingIcon = {
                    TextButton(onClick = ::add, enabled = !busy && !full && query.isNotBlank()) { Text("Add") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Skin.chrome.muted)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "${items.size} of ${Locations.MAX} · hold a row or drag its handle to reorder",
                fontSize = 12.sp, color = Skin.chrome.muted,
            )
            Spacer(Modifier.height(6.dp))
            ReorderableLocations(
                items = items,
                onReorder = { items = it },
                onDrop = { Locations.save(context, items) },
            ) { loc, handle ->
                LocationRow(
                    loc = loc,
                    handle = handle,
                    locationMode = locationMode,
                    onAllow = { askPermission.launch(LocationProvider.permissions) },
                    onRename = { renaming = loc },
                    onDelete = { delete(loc) },
                )
            }
        }
    }

    renaming?.let { loc ->
        NicknameDialog(
            loc,
            onDismiss = { renaming = null },
            onSave = { nick ->
                Locations.rename(context, loc.id, nick)
                reload()
                renaming = null
            },
        )
    }
}

/**
 * A plain [Column] whose rows can be dragged into a new order — the list is capped at
 * [Locations.MAX], so there is nothing for a lazy list to save. A row lifts on long-press
 * anywhere, or at once from its handle; it follows the finger and trades places with a
 * neighbour once it has covered half that neighbour's height. [onReorder] fires on every
 * swap so the list re-lays out under the finger; [onDrop] once, when the finger lifts.
 */
@Composable
private fun ReorderableLocations(
    items: List<SavedLocation>,
    onReorder: (List<SavedLocation>) -> Unit,
    onDrop: () -> Unit,
    row: @Composable (SavedLocation, Modifier) -> Unit,
) {
    // The gesture coroutines outlive recompositions, so they read the list through these.
    val latest by rememberUpdatedState(items)
    val reorder by rememberUpdatedState(onReorder)
    val gapPx = with(LocalDensity.current) { ROW_GAP.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    fun drag(dy: Float) {
        val id = draggingId ?: return
        offset += dy
        val list = latest
        val i = list.indexOfFirst { it.id == id }
        val next = list.getOrNull(i + 1)
        val prev = list.getOrNull(i - 1)
        if (next != null && offset > (heights[next.id] ?: 0) / 2f) {
            reorder(Locations.moved(list, i, i + 1))
            offset -= (heights[next.id] ?: 0) + gapPx
        } else if (prev != null && offset < -(heights[prev.id] ?: 0) / 2f) {
            reorder(Locations.moved(list, i, i - 1))
            offset += (heights[prev.id] ?: 0) + gapPx
        }
    }

    fun end() {
        draggingId = null
        offset = 0f
        onDrop()
    }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        items.forEach { loc ->
            key(loc.id) {
                val lifted = draggingId == loc.id
                val start: (Any) -> Unit = { draggingId = loc.id; offset = 0f }
                Row(
                    Modifier
                        .onSizeChanged { heights[loc.id] = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) offset else 0f }
                        .shadow(if (lifted) 6.dp else 0.dp, RoundedCornerShape(10.dp))
                        .pointerInput(loc.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = start,
                                onDrag = { change, amount -> change.consume(); drag(amount.y) },
                                onDragEnd = ::end,
                                onDragCancel = ::end,
                            )
                        },
                ) {
                    row(
                        loc,
                        Modifier.pointerInput(loc.id) {
                            detectDragGestures(
                                onDragStart = start,
                                onDrag = { change, amount -> change.consume(); drag(amount.y) },
                                onDragEnd = ::end,
                                onDragCancel = ::end,
                            )
                        },
                    )
                }
            }
        }
    }
}

private val ROW_GAP = 6.dp

@Composable
private fun LocationRow(
    loc: SavedLocation,
    handle: Modifier,
    locationMode: LocationProvider.LocationMode,
    onAllow: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val chrome = Skin.chrome
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(chrome.card)
            .border(1.dp, chrome.hairline, RoundedCornerShape(10.dp))
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Menu,
            contentDescription = "Drag to reorder",
            tint = chrome.faint,
            modifier = handle.padding(12.dp).size(20.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loc.isCurrent) {
                    Icon(
                        painterResource(locationMode.iconRes),
                        contentDescription = locationMode.label,
                        tint = chrome.muted,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    loc.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = chrome.heading,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val detail = when {
                loc.isCurrent && locationMode == LocationProvider.LocationMode.UNAVAILABLE ->
                    "Location off or not allowed — tap to allow · last: ${loc.placeLabel}"
                loc.isCurrent -> "Follows your phone · ${loc.placeLabel}"
                loc.nickname != null -> "${loc.placeLabel} · ${loc.lat}, ${loc.lon}"
                else -> "${loc.lat}, ${loc.lon}"
            }
            Text(
                detail, fontSize = 12.sp, color = chrome.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = if (loc.isCurrent && locationMode == LocationProvider.LocationMode.UNAVAILABLE)
                    Modifier.clickable(onClick = onAllow) else Modifier,
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = "Nickname", tint = chrome.muted, modifier = Modifier.size(18.dp))
        }
        if (!loc.isCurrent) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = chrome.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NicknameDialog(loc: SavedLocation, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(loc.nickname.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Skin.chrome.card,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Nickname", fontFamily = Heading, fontSize = 20.sp, color = Skin.chrome.heading) },
        text = {
            Column {
                Text(
                    "Shown in the bottom selector instead of “${if (loc.isCurrent) "Current location" else loc.placeLabel}”. " +
                        "Leave blank to use that.",
                    fontSize = 12.sp, color = Skin.chrome.muted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Locations.NICKNAME_MAX) },
                    singleLine = true,
                    placeholder = { Text("e.g. Home, NYC, Mom") },
                    supportingText = { Text("${text.length}/${Locations.NICKNAME_MAX}") },
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
