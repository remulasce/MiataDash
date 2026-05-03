package dev.kirker.miatadash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Big primary gauge — speed / RPM. Glanceable.
 *
 * The numeric field is right-justified into a fixed-width slot so the digits don't shift
 * left/right as the value's digit count changes. With our monospace `displayLarge` font,
 * `value.padStart(valueWidth)` produces a deterministic pixel width.
 *
 * The unit suffix (rpm/mph) is vertically centered on the digit field, not baseline-aligned
 * to its descender — that previously made the unit drop visibly below the digits.
 */
@Composable
fun PrimaryGauge(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueWidth: Int = 5,    // 4 digits worth of RPM headroom (≤ 99,999)
) {
    val padded = if (value.length >= valueWidth) value else value.padStart(valueWidth)
    Column(modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(padded, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.width(12.dp))
            Text(
                unit,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/** Compact secondary tile — coolant / IAT / etc. */
@Composable
fun SecondaryTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge)
                if (unit.isNotBlank()) Text("  $unit", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

/** Horizontal bar — wheel speeds / fuel trim sign. */
@Composable
fun BarMeter(
    label: String,
    fraction: Float,                    // 0f..1f
    valueText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.bodyLarge)
        }
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** Color helpers used by readiness chips and DTC severity. */
object StatusColors {
    val Ready = Color(0xFF1B873B)
    val Warn = Color(0xFFE5A100)
    val Bad = Color(0xFFB31312)
    val Unknown = Color(0xFF8E8E8E)
}
