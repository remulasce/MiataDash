package dev.kirker.miatadash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kirker.miatadash.core.braking.BrakeEvent
import dev.kirker.miatadash.core.braking.CornerSlipStats
import dev.kirker.miatadash.core.braking.SrsRawStats
import dev.kirker.miatadash.core.units.Units

/**
 * Card showing the most recent completed hard-braking event (or an idle placeholder).
 *
 *  - Entry / exit speed in the user's preferred unit (mph / kph)
 *  - A [WheelSpeedGraph] snapshot over the captured event window
 *  - A four-corner slip grid: peak slip, average slip, and ABS pulse count per corner
 *
 * Used on both the live dashboard (most-recent event only) and the Brake Log diagnostic
 * screen (one card per stored event).
 *
 * @param events Most-recent-first list of completed events. Only the first entry is rendered
 *               here; the diagnostic log screen maps each event to its own card.
 * @param showPlaceholder When true, show an "awaiting first event" message when [events] is
 *                        empty. Set false when the caller (e.g. log screen) handles the empty
 *                        state itself.
 */
@Composable
fun BrakeReportCard(
    events: List<BrakeEvent>,
    modifier: Modifier = Modifier,
    showPlaceholder: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            // Header row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Braking Performance", style = MaterialTheme.typography.titleLarge)
                if (events.size > 1) {
                    Text(
                        "${events.size} events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }

            if (events.isEmpty()) {
                if (showPlaceholder) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Waiting for a hard-braking event. Any deceleration above ~0.4 g " +
                        "will trigger capture.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                return@Column
            }

            BrakeEventDetail(event = events.first())
        }
    }
}

/**
 * The body of a single [BrakeEvent] report: speed summary, delta graph, and four-corner
 * slip grid. Extracted so the log screen can render multiple events without nesting cards.
 */
@Composable
fun BrakeEventDetail(event: BrakeEvent, modifier: Modifier = Modifier) {
    val startSpd = Units.speed(event.startSpeedKph)
    val endSpd   = Units.speed(event.endSpeedKph)
    val unit     = Units.speedLabel
    val durSec   = event.durationMs / 1000.0

    Column(modifier) {
        Spacer(Modifier.height(6.dp))

        // Speed summary
        Text(
            "%.0f → %.0f %s  ·  %.1f s".format(startSpd, endSpd, unit, durSec),
            style = MaterialTheme.typography.titleMedium,
        )

        // One-line slip pattern analysis
        Text(
            event.slipSummary(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        Spacer(Modifier.height(8.dp))

        // Wheel-speed delta snapshot over the event window using the BrakeSample overload.
        WheelSpeedGraph(
            brakeHistory = event.samples,
            windowMs     = event.sampleWindowMs,
            modifier     = Modifier.padding(bottom = 8.dp),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(8.dp))

        Text(
            "Corner slip",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(6.dp))

        // Four-corner grid — front axle on top, rear on bottom, driver's left = FL/RL.
        Row(Modifier.fillMaxWidth()) {
            CornerSlipCell("FL", event.fl, Modifier.weight(1f).padding(end = 4.dp))
            CornerSlipCell("FR", event.fr, Modifier.weight(1f).padding(start = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            CornerSlipCell("RL", event.rl, Modifier.weight(1f).padding(end = 4.dp))
            CornerSlipCell("RR", event.rr, Modifier.weight(1f).padding(start = 4.dp))
        }

        // SRS raw calibration data — only shown if 0x430 was live during this event.
        val srs0 = event.srsRaw0
        val srs2 = event.srsRaw2
        if (srs0 != null || srs2 != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))
            Text(
                "SRS raw (0x430) — calibration data",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            SrsRawRow("Ch 0 (bytes 0-1)", srs0)
            SrsRawRow("Ch 2 (bytes 2-3)", srs2)
            Text(
                "Divide peak ch-2 change from rest by LONG G to get LSB/g scale.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun SrsRawRow(label: String, stats: SrsRawStats?) {
    if (stats == null) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            "min %.0f  max %.0f  avg %.0f  (n=%d)".format(stats.min, stats.max, stats.avg, stats.count),
            Modifier.weight(2f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * One corner in the four-corner slip grid.
 *
 * Background tint reflects peak-slip severity:
 *   < −5 kph peak  → red   (heavy lock-up)
 *   −3 to −5 kph   → amber (moderate slip, ABS active)
 *   > −3 kph        → surface (minor / noise)
 */
@Composable
fun CornerSlipCell(label: String, stats: CornerSlipStats, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val tint: Color = when {
        stats.peakSlipKph < -5.0 ->
            if (isDark) Color(0xFFB31312).copy(alpha = 0.35f) else Color(0xFFB31312).copy(alpha = 0.18f)
        stats.peakSlipKph < -3.0 ->
            if (isDark) Color(0xFFE5A100).copy(alpha = 0.35f) else Color(0xFFE5A100).copy(alpha = 0.18f)
        else -> Color.Transparent
    }

    val peakDisplay = "%.1f".format(Units.speed(stats.peakSlipKph))
    val avgDisplay  = "%.1f".format(Units.speed(stats.avgSlipKph))
    val unit        = Units.speedLabel

    Box(
        modifier
            .background(tint, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                "$peakDisplay $unit peak",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "$avgDisplay $unit avg",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
            )
            if (stats.absPulses > 0) {
                Text(
                    "ABS ×${stats.absPulses}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
