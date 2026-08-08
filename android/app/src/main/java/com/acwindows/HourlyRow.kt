package com.acwindows

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One column of an [HourlyValueRow]: what to show for a single forecast point. */
data class HourlyCell(
    val primary: String,        // big value, top
    val glyph: WxGlyph?,        // icon, middle (null = blank slot, keeps rows aligned)
    val secondary: String?,     // small value, below the icon
    val time: String,           // hour label, bottom ("Now" for the current row)
)

/**
 * A horizontally-scrolling strip of hour-by-hour numbers, one fixed-width column per
 * [HourlyCell]. Sits directly above the chart it summarizes (see [AbsoluteCharts]) so the
 * actual values are readable before you even look at the line/bar chart below.
 */
@Composable
fun HourlyValueRow(cells: List<HourlyCell>, modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.forEach { cell ->
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface, maxLines = 1)
                // Reserve the 20dp icon slot even when there's no glyph, so a run of
                // dry/glyph-less hours doesn't shift the secondary/time rows out of
                // alignment with their wet neighbours.
                if (cell.glyph != null) WeatherGlyph(cell.glyph, size = 20.dp) else Spacer(Modifier.size(20.dp))
                Text(cell.secondary ?: "", fontSize = 11.sp, color = onVar, maxLines = 1)
                Text(cell.time, fontSize = 11.sp, color = onVar, maxLines = 1)
            }
        }
    }
}
