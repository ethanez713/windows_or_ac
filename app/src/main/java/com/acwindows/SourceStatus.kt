package com.acwindows

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Prominent header for the current data view: where the forecast is for, how many sources
 * were successfully polled (X/N), the consensus confidence colour, and when this data was
 * fetched. Tap the place to manage locations; tap anywhere else for the per-source
 * breakdown — handy when the reported numbers look off versus a local reading.
 */
@Composable
internal fun SourceStatusHeader(
    state: AppState,
    updatedAtMs: Long,
    place: String,
    pin: LocationProvider.LocationMode?,
    onLocationClick: () -> Unit,
    onClick: () -> Unit,
) {
    val c = state.consensus
    val ok = c.sourcesOk.size
    val total = maxOf(c.sourcesAttempted, ok + c.sourcesFailed.size)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val dot = confidenceColor(c.confidence)
    val updated = remember(updatedAtMs) {
        if (updatedAtMs > 0)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(updatedAtMs))
        else null
    }
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() },
        color = Skin.chrome.card,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Condensed single line: "📍 City, ST ▾ · ● 6/6 · 3:45 PM  ›". The city name
            // ellipsizes inside the weighted inner row so the count/time never get pushed
            // off; the chevron stays pinned to the right edge. The place is its own
            // clickable — the tap that opens the Locations screen — and the rest of the
            // row still opens the source breakdown.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                LocationChip(Modifier.weight(1f, fill = false), onLocationClick) {
                    pin?.let {
                        LocationPin(it, onVar)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        place,
                        Modifier.weight(1f, fill = false),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(" ▾", fontSize = 12.sp, color = primary)
                }
                Text(" · ", fontSize = 13.sp, color = onVar)
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text("$ok/$total", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                updated?.let { Text(" · $it", fontSize = 12.sp, color = onVar, maxLines = 1, softWrap = false) }
            }
            Text(" ›", fontSize = 13.sp, color = primary)
        }
    }
}

@Composable
private fun LocationPin(mode: LocationProvider.LocationMode, tint: Color) {
    Icon(
        painterResource(mode.iconRes),
        contentDescription = mode.label,
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

/** The header's tappable location area — its own click target inside the wider banner. */
@Composable
private fun LocationChip(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) = Row(
    modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
)

private fun confidenceColor(confidence: String): Color = when (confidence) {
    "high" -> OpenGreen
    "degraded" -> AcBlue
    "conflict" -> UnknownRed
    else -> ClosedSlate
}

private data class SourceMeta(val name: String, val url: String?)

private fun sourceMeta(source: String): SourceMeta = when (source) {
    "open-meteo" -> SourceMeta("Open-Meteo", "https://open-meteo.com/")
    "nws" -> SourceMeta("NWS · weather.gov", "https://www.weather.gov/")
    "metno" -> SourceMeta("MET Norway", "https://www.met.no/en")
    else -> SourceMeta(source, null)
}

/** One readable line of whatever a source actually returned. */
private fun sourceValues(r: SourceReading, unit: TempUnit): String {
    val parts = mutableListOf<String>()
    r.tempF?.let { parts.add(unit.degUnit(it)) }
    r.dewPointF?.let { parts.add("dew ${unit.deg(it)}") }
    r.humidityPct?.let { parts.add("${it.roundToInt()}% RH") }
    r.windMph?.let { parts.add("${it.roundToInt()} mph") }
    r.aqi?.let { parts.add("AQI $it") }
    return if (parts.isEmpty()) "no values" else parts.joinToString(" · ")
}

/** The merged numbers the app actually reports (the average across the OK sources). */
private fun consensusValues(c: Consensus, unit: TempUnit): String {
    val parts = mutableListOf<String>()
    c.tempF?.let { parts.add(unit.degUnit(it)) }
    c.dewPointF?.let { parts.add("dew ${unit.deg(it)}") }
    c.humidityPct?.let { parts.add("${it.roundToInt()}% RH") }
    c.windMph?.let { parts.add("${it.roundToInt()} mph") }
    c.aqi?.let { parts.add("AQI $it") }
    return if (parts.isEmpty()) "no values" else parts.joinToString(" · ")
}

@Composable
internal fun SourceDetailDialog(sources: List<SourceReading>, consensus: Consensus, unit: TempUnit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Data sources") },
        text = {
            Column {
                // What the app reports = the average of the OK sources below. Surfaced up top
                // so a value that looks off can be traced to which source is pulling it.
                Text("Reported (averaged)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(consensusValues(consensus, unit), fontSize = 12.sp, color = onVar)
                Spacer(Modifier.height(8.dp))
                Text("Per source", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (sources.isEmpty()) Text("No source detail available.", color = onVar)
                sources.forEach { r ->
                    val meta = sourceMeta(r.source)
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.ok) OpenGreen else UnknownRed))
                            Spacer(Modifier.width(8.dp))
                            Text(meta.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (meta.url != null) {
                                Text(
                                    "open ↗", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meta.url))) }
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        if (r.ok) Text(sourceValues(r, unit), fontSize = 12.sp, color = onVar)
                        else Text(r.error ?: "unavailable", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Consensus: ${consensus.confidence}" +
                        (if (consensus.tempSpreadF > 0) " · temp spread ${consensus.tempSpreadF.roundToInt()}°F" else ""),
                    fontSize = 11.sp, color = onVar,
                )
            }
        },
    )
}
