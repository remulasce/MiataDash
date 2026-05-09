package dev.kirker.miatadash.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.jvm.JvmName
import dev.kirker.miatadash.core.braking.BrakeSample
import dev.kirker.miatadash.feature.dashboard.GForcePoint
import dev.kirker.miatadash.feature.dashboard.WheelSpeedSample
import dev.kirker.miatadash.core.units.Units

/**
 * Big primary gauge — speed / RPM. Glanceable.
 *
 * The numeric field is right-justified into a fixed-width slot so the digits don't shift
 * left/right as the value's digit count changes. With our monospace font,
 * `value.padStart(valueWidth)` produces a deterministic pixel width.
 *
 * When [compact] is true, the gauge shrinks from displayLarge → displayMedium so the
 * dashboard can reclaim vertical space for the wheel-speed graph.
 */
@Composable
fun PrimaryGauge(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueWidth: Int = 5,
    compact: Boolean = false,
) {
    val isNoData   = value == "—"
    // Only pad real values — padding "—" produces "   —" in monospace which looks broken.
    val display    = when {
        isNoData              -> "—"
        value.length >= valueWidth -> value
        else                  -> value.padStart(valueWidth)
    }
    val valueStyle = if (compact) MaterialTheme.typography.displayMedium
                     else         MaterialTheme.typography.displayLarge
    val unitStyle  = if (compact) MaterialTheme.typography.titleLarge
                     else         MaterialTheme.typography.headlineMedium
    val vpad       = if (compact) 6.dp else 12.dp
    val dimAlpha   = 0.22f   // alpha for both value and unit when no data yet

    Column(modifier.padding(vertical = vpad), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                display,
                style = valueStyle,
                color = MaterialTheme.colorScheme.onBackground.copy(
                    alpha = if (isNoData) dimAlpha else 1f,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                unit,
                style = unitStyle,
                color = MaterialTheme.colorScheme.onBackground.copy(
                    alpha = if (isNoData) dimAlpha else 0.6f,
                ),
            )
        }
    }
}

/**
 * Compact secondary tile — coolant / IAT / etc.
 *
 * [containerColor] overrides the default surface color. Use [coolantTileColor] /
 * [iatTileColor] to get temperature-appropriate tints.
 */
@Composable
fun SecondaryTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    Surface(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        tonalElevation = if (containerColor == MaterialTheme.colorScheme.surface) 2.dp else 0.dp,
    ) {
        val isNoData = value == "—"
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isNoData) 0.25f else 1f),
                )
                if (unit.isNotBlank()) Text(
                    "  $unit",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isNoData) 0.2f else 0.6f),
                )
            }
        }
    }
}

// ── Temperature color helpers ─────────────────────────────────────────────────

/**
 * Background tint for the coolant temperature tile.
 *
 * Thresholds match Mazda NC real-world operating experience (Fahrenheit reference):
 *   Blue   < 160 °F (71 °C)  — cold, engine still warming up
 *   Green  160–200 °F (71–93 °C) — normal operating range (up to temp at 180 °F / 82 °C)
 *   Amber  200–220 °F (93–104 °C) — running warm, keep an eye on it
 *   Red    ≥ 220 °F (104 °C)  — overtemp warning, pull over soon
 */
fun coolantTileColor(tempC: Double, isDark: Boolean): Color = when {
    tempC < 71.1  -> if (isDark) Color(0xFF1565C0).copy(alpha = 0.35f) else Color(0xFF1565C0).copy(alpha = 0.18f)
    tempC < 93.3  -> if (isDark) Color(0xFF1B873B).copy(alpha = 0.30f) else Color(0xFF1B873B).copy(alpha = 0.15f)
    tempC < 104.4 -> if (isDark) Color(0xFFE5A100).copy(alpha = 0.40f) else Color(0xFFE5A100).copy(alpha = 0.22f)
    else          -> if (isDark) Color(0xFFB31312).copy(alpha = 0.50f) else Color(0xFFB31312).copy(alpha = 0.28f)
}

/**
 * Background tint for the intake air temperature tile.
 * Green  < 50 °C — normal
 * Amber 50–70   — heat soak zone
 * Red    > 70   — significant induction heat, power loss likely
 */
fun iatTileColor(tempC: Double, isDark: Boolean): Color = when {
    tempC < 50.0  -> if (isDark) Color(0xFF1B873B).copy(alpha = 0.30f) else Color(0xFF1B873B).copy(alpha = 0.15f)
    tempC < 70.0  -> if (isDark) Color(0xFFE5A100).copy(alpha = 0.40f) else Color(0xFFE5A100).copy(alpha = 0.22f)
    else          -> if (isDark) Color(0xFFB31312).copy(alpha = 0.50f) else Color(0xFFB31312).copy(alpha = 0.28f)
}

// ── Wheel speed strip chart ───────────────────────────────────────────────────

private val WheelColors = listOf(
    Color(0xFF4FC3F7), // FL — sky blue
    Color(0xFF81C784), // FR — green
    Color(0xFFFFB74D), // RL — amber
    Color(0xFFEF9A9A), // RR — soft red
)
private val WheelLabels = listOf("FL", "FR", "RL", "RR")

/**
 * Rolling 10-second strip chart showing each wheel's speed **delta** from the ECU's own
 * vehicle speed reading (0x201).
 *
 * Why deltas instead of absolute speeds?
 *  - The speedometer already shows absolute speed — this adds no new information.
 *  - Deltas reveal what the speedometer can't: brake lock-up (wheel drops below vehicle
 *    speed → large negative delta), wheelspin (wheel races ahead → positive delta), and
 *    cornering geometry (inside wheels spin slower than outside wheels).
 *
 * Y-axis is centred on 0 (= ECU speed). The range auto-scales to ±[maxAbsDelta] with a
 * minimum of ±[MIN_DELTA_KPH] so the chart is readable even when everything is calm.
 * Each [WheelSpeedSample] carries its own [WheelSpeedSample.vehicleKph] so historical
 * deltas are correct even as vehicle speed changes across the window.
 */
@Composable
fun WheelSpeedGraph(
    history: List<WheelSpeedSample>,
    modifier: Modifier = Modifier,
    windowMs: Long = 10_000L,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle   = MaterialTheme.typography.labelSmall
    val gridColor    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val axisColor    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    val zeroColor    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f)

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            // Legend row + "Δ from ECU speed" annotation on the right
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelLabels.forEachIndexed { i, lbl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(14.dp).height(3.dp)
                                .background(WheelColors[i], RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(lbl, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Δ from ECU speed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                )
            }

            // ── Pre-compute deltas and y-range ───────────────────────────────────
            val nowMs   = remember(history) { history.lastOrNull()?.tsMs ?: System.currentTimeMillis() }
            val startMs = nowMs - windowMs

            // Per-sample delta getters — use vehicleKph stored at sample time for correctness.
            val wheelDeltaGetters: List<(WheelSpeedSample) -> Double> = listOf(
                { it.fl - it.vehicleKph },
                { it.fr - it.vehicleKph },
                { it.rl - it.vehicleKph },
                { it.rr - it.vehicleKph },
            )

            val allDeltas = history.flatMap { s -> wheelDeltaGetters.map { it(s) } }
            // Symmetric ±range; floor so the chart isn't a flat line at rest.
            val maxAbsDelta = (allDeltas.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0)
                .coerceAtLeast(MIN_DELTA_KPH) * 1.15   // 15% head-room so lines don't kiss the edge

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                val w = size.width
                val h = size.height
                val padL = 30f  // left margin for signed Y-axis labels
                val padB = 18f  // bottom margin for time axis labels
                val plotW = w - padL
                val plotH = h - padB

                fun xOf(tsMs: Long)    = padL + ((tsMs - startMs).toFloat() / windowMs * plotW)
                // delta = 0 → vertical centre; positive → up (smaller y).
                fun yOf(delta: Double) = plotH / 2f - (delta / maxAbsDelta * (plotH / 2f)).toFloat()

                val yZero = yOf(0.0)

                // ── Subtle grid at ±50% of range ────────────────────────────────
                for (frac in listOf(-0.5, 0.5)) {
                    drawLine(
                        color = gridColor,
                        start = Offset(padL, yOf(maxAbsDelta * frac)),
                        end   = Offset(w,    yOf(maxAbsDelta * frac)),
                        strokeWidth = 1f,
                    )
                }

                // ── Zero line — more visible than the grid, anchors the reading ─
                drawLine(
                    color = zeroColor,
                    start = Offset(padL, yZero),
                    end   = Offset(w,    yZero),
                    strokeWidth = 1.5f,
                )

                // ── Left axis ───────────────────────────────────────────────────
                drawLine(color = axisColor, start = Offset(padL, 0f), end = Offset(padL, plotH), strokeWidth = 1f)

                // ── Y-axis labels: top / zero / bottom ──────────────────────────
                val axStyle      = labelStyle.copy(color = axisColor)
                val rangeDisplay = Units.speed(maxAbsDelta).toInt()
                val topLabel     = "+$rangeDisplay"
                val zeroLabel    = "0"
                val botLabel     = "−$rangeDisplay"

                val zeroMeasured = textMeasurer.measure(zeroLabel, axStyle)
                drawText(textMeasurer, topLabel,  topLeft = Offset(0f, yOf(maxAbsDelta) + 2f),                                      style = axStyle)
                drawText(textMeasurer, zeroLabel, topLeft = Offset(0f, yZero - zeroMeasured.size.height / 2f),                       style = axStyle)
                drawText(textMeasurer, botLabel,  topLeft = Offset(0f, yOf(-maxAbsDelta) - zeroMeasured.size.height - 2f),           style = axStyle)

                // ── Time axis labels ────────────────────────────────────────────
                val timeStyle   = labelStyle.copy(color = axisColor)
                val nowMeasured = textMeasurer.measure("now", timeStyle)
                drawText(textMeasurer, "−${windowMs / 1000}s", topLeft = Offset(padL + 2f, h - padB + 2f),                          style = timeStyle)
                drawText(textMeasurer, "now",                   topLeft = Offset(w - nowMeasured.size.width - 4f, h - padB + 2f),    style = timeStyle)

                if (history.isEmpty()) return@Canvas

                // ── Four wheel delta lines ───────────────────────────────────────
                wheelDeltaGetters.forEachIndexed { idx, getDelta ->
                    val path = Path()
                    var first = true
                    for (sample in history) {
                        val x = xOf(sample.tsMs)
                        val y = yOf(getDelta(sample))
                        if (first) { path.moveTo(x, y); first = false }
                        else path.lineTo(x, y)
                    }
                    drawPath(
                        path  = path,
                        color = WheelColors[idx],
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

/** Minimum y-axis half-range in kph — keeps the chart readable when all wheels match ECU speed. */
private const val MIN_DELTA_KPH = 5.0

/**
 * [WheelSpeedGraph] overload for replaying a stored [BrakeSample] list.
 *
 * Converts each [BrakeSample] to the [WheelSpeedSample] type internally, letting the brake
 * report screen use the same chart without creating a UI-layer dependency in the braking core.
 *
 * [windowMs] defaults to the full sample span + 200 ms buffer. Pass an explicit value to pin
 * the x-axis to a known window (e.g. the live 10-second strip chart).
 */
@JvmName("WheelSpeedGraphFromBrake")
@Composable
fun WheelSpeedGraph(
    brakeHistory: List<BrakeSample>,
    modifier: Modifier = Modifier,
    windowMs: Long = if (brakeHistory.size >= 2)
        brakeHistory.last().tsMs - brakeHistory.first().tsMs + 200L
    else 5_000L,
) {
    val converted = remember(brakeHistory) {
        brakeHistory.map { s -> WheelSpeedSample(s.tsMs, s.fl, s.fr, s.rl, s.rr, s.vehicleKph) }
    }
    WheelSpeedGraph(history = converted, modifier = modifier, windowMs = windowMs)
}

// ── Wheel speed 2×2 corner grid ──────────────────────────────────────────────

/**
 * 2×2 corner grid — one mini strip chart per wheel, arranged to mirror the car's
 * physical corner layout:
 *
 *     ┌────────┬────────┐
 *     │  FL    │  FR    │   ← front axle
 *     ├────────┼────────┤
 *     │  RL    │  RR    │   ← rear axle
 *     └────────┴────────┘
 *
 * All four panels share a **single Y-scale** computed across every corner at once, so
 * magnitude comparisons across panels are valid — a 3 kph excursion in FL is drawn at
 * the same height as a 3 kph excursion in RR.
 *
 * Use this when you need to isolate individual corner behaviour (ABS pulses, brake
 * lock-up, cornering understeer). For a traditional 4-line overlay use [WheelSpeedGraph].
 */
@Composable
fun WheelSpeedCornerGrid(
    history: List<WheelSpeedSample>,
    modifier: Modifier = Modifier,
    windowMs: Long = 10_000L,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle   = MaterialTheme.typography.labelSmall
    val gridColor    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val axisColor    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    val zeroColor    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f)

    val nowMs   = remember(history) { history.lastOrNull()?.tsMs ?: System.currentTimeMillis() }
    val startMs = nowMs - windowMs

    // Shared Y-scale — derived from all four corners so cross-panel comparisons are valid.
    val maxAbsDelta = remember(history) {
        val allDeltas = history.flatMap { s ->
            listOf(s.fl - s.vehicleKph, s.fr - s.vehicleKph, s.rl - s.vehicleKph, s.rr - s.vehicleKph)
        }
        (allDeltas.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0)
            .coerceAtLeast(MIN_DELTA_KPH) * 1.15
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape    = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            // ── Front axle row (FL left, FR right) ───────────────────────────
            Row(Modifier.fillMaxWidth()) {
                CornerMiniChart(
                    label = "FL", history = history, getDelta = { it.fl - it.vehicleKph },
                    color = WheelColors[0], maxAbsDelta = maxAbsDelta,
                    startMs = startMs, windowMs = windowMs,
                    showLeftAxis = true, showTimeAxis = false,
                    textMeasurer = textMeasurer, labelStyle = labelStyle,
                    gridColor = gridColor, axisColor = axisColor, zeroColor = zeroColor,
                    modifier = Modifier.weight(1f),
                )
                CornerMiniChart(
                    label = "FR", history = history, getDelta = { it.fr - it.vehicleKph },
                    color = WheelColors[1], maxAbsDelta = maxAbsDelta,
                    startMs = startMs, windowMs = windowMs,
                    showLeftAxis = false, showTimeAxis = false,
                    textMeasurer = textMeasurer, labelStyle = labelStyle,
                    gridColor = gridColor, axisColor = axisColor, zeroColor = zeroColor,
                    modifier = Modifier.weight(1f),
                )
            }
            // ── Rear axle row (RL left, RR right) ────────────────────────────
            // Bottom row carries the time-axis labels ("−10s" on RL, "now" on RR).
            Row(Modifier.fillMaxWidth()) {
                CornerMiniChart(
                    label = "RL", history = history, getDelta = { it.rl - it.vehicleKph },
                    color = WheelColors[2], maxAbsDelta = maxAbsDelta,
                    startMs = startMs, windowMs = windowMs,
                    showLeftAxis = true, showTimeAxis = true,
                    textMeasurer = textMeasurer, labelStyle = labelStyle,
                    gridColor = gridColor, axisColor = axisColor, zeroColor = zeroColor,
                    modifier = Modifier.weight(1f),
                )
                CornerMiniChart(
                    label = "RR", history = history, getDelta = { it.rr - it.vehicleKph },
                    color = WheelColors[3], maxAbsDelta = maxAbsDelta,
                    startMs = startMs, windowMs = windowMs,
                    showLeftAxis = false, showTimeAxis = true,
                    textMeasurer = textMeasurer, labelStyle = labelStyle,
                    gridColor = gridColor, axisColor = axisColor, zeroColor = zeroColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One panel of [WheelSpeedCornerGrid]. Draws a single-line strip chart for one wheel corner.
 *
 * [showLeftAxis] — draw the Y-axis line and ±range labels (left column panels only).
 * [showTimeAxis] — draw "−Ns / now" time labels along the bottom (bottom row panels only).
 */
@Composable
private fun CornerMiniChart(
    label: String,
    history: List<WheelSpeedSample>,
    getDelta: (WheelSpeedSample) -> Double,
    color: Color,
    maxAbsDelta: Double,
    startMs: Long,
    windowMs: Long,
    showLeftAxis: Boolean,
    showTimeAxis: Boolean,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
    axisColor: Color,
    zeroColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .height(78.dp)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        val w     = size.width
        val h     = size.height
        val padL  = if (showLeftAxis) 28f else 4f
        val padB  = if (showTimeAxis) 16f else 4f
        val plotW = w - padL
        val plotH = h - padB

        fun xOf(tsMs: Long)    = padL + ((tsMs - startMs).toFloat() / windowMs * plotW)
        fun yOf(delta: Double) = plotH / 2f - (delta / maxAbsDelta * (plotH / 2f)).toFloat()
        val yZero = yOf(0.0)

        // ── Grid lines at ±50% of scale ──────────────────────────────────
        for (frac in listOf(-0.5, 0.5)) {
            drawLine(
                color = gridColor,
                start = Offset(padL, yOf(maxAbsDelta * frac)),
                end   = Offset(w,    yOf(maxAbsDelta * frac)),
                strokeWidth = 1f,
            )
        }

        // ── Zero line ────────────────────────────────────────────────────
        drawLine(color = zeroColor, start = Offset(padL, yZero), end = Offset(w, yZero), strokeWidth = 1.5f)

        // ── Left axis line ───────────────────────────────────────────────
        if (showLeftAxis) {
            drawLine(color = axisColor, start = Offset(padL, 0f), end = Offset(padL, plotH), strokeWidth = 1f)
        }

        // ── Y-axis labels: +range / 0 / −range (left column only) ────────
        if (showLeftAxis) {
            val axStyle      = labelStyle.copy(color = axisColor)
            val rangeDisplay = Units.speed(maxAbsDelta).toInt()
            val zeroH        = textMeasurer.measure("0", axStyle).size.height.toFloat()
            drawText(textMeasurer, "+$rangeDisplay", topLeft = Offset(0f, yOf(maxAbsDelta) + 2f),                 style = axStyle)
            drawText(textMeasurer, "0",              topLeft = Offset(0f, yZero - zeroH / 2f),                    style = axStyle)
            drawText(textMeasurer, "−$rangeDisplay", topLeft = Offset(0f, yOf(-maxAbsDelta) - zeroH - 2f),        style = axStyle)
        }

        // ── Time-axis labels: "−Ns" on the left panel, "now" on the right ─
        if (showTimeAxis) {
            val timeStyle = labelStyle.copy(color = axisColor)
            if (showLeftAxis) {
                drawText(textMeasurer, "−${windowMs / 1000}s", topLeft = Offset(padL + 2f, h - padB + 2f), style = timeStyle)
            } else {
                val nowW = textMeasurer.measure("now", timeStyle).size.width.toFloat()
                drawText(textMeasurer, "now", topLeft = Offset(w - nowW - 4f, h - padB + 2f),              style = timeStyle)
            }
        }

        // ── Corner label — colored, top-right of the plot area ────────────
        val cornerStyle = labelStyle.copy(color = color)
        val cornerW     = textMeasurer.measure(label, cornerStyle).size.width.toFloat()
        drawText(textMeasurer, label, topLeft = Offset(w - cornerW - 4f, 4f), style = cornerStyle)

        if (history.isEmpty()) return@Canvas

        // ── Single-corner delta line ──────────────────────────────────────
        val path = Path()
        var first = true
        for (sample in history) {
            val x = xOf(sample.tsMs)
            val y = yOf(getDelta(sample))
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * [WheelSpeedCornerGrid] overload for replaying a stored [BrakeSample] list.
 * Mirrors the [WheelSpeedGraph] overload so either chart can be dropped into the brake report.
 */
@JvmName("WheelSpeedCornerGridFromBrake")
@Composable
fun WheelSpeedCornerGrid(
    brakeHistory: List<BrakeSample>,
    modifier: Modifier = Modifier,
    windowMs: Long = if (brakeHistory.size >= 2)
        brakeHistory.last().tsMs - brakeHistory.first().tsMs + 200L
    else 5_000L,
) {
    val converted = remember(brakeHistory) {
        brakeHistory.map { s -> WheelSpeedSample(s.tsMs, s.fl, s.fr, s.rl, s.rr, s.vehicleKph) }
    }
    WheelSpeedCornerGrid(history = converted, modifier = modifier, windowMs = windowMs)
}

// ── G-Force X-Y scatter plot ──────────────────────────────────────────────────

/**
 * Circular G-meter: a real-time X-Y scatter plot showing lateral vs longitudinal G.
 *
 * **Axes**
 *  - X : lateral G — positive (right side of plot) = rightward force = car turning left
 *  - Y : longitudinal G — positive (top of plot) = braking / deceleration
 *
 * **Visual elements**
 *  - Outer ring = ±[rangeG] boundary
 *  - Middle ring = ±1 g reference (handy ABS threshold at ~0.9 g)
 *  - Inner ring = ±0.5 g reference (moderate braking / cornering)
 *  - Crosshairs with "BRAKE / ACCEL" labels
 *  - Fading trail over [G_PLOT_TRAIL_MS] ms — older = more transparent / smaller dot
 *  - Bright current-position dot with a soft glow ring
 *
 * [history] should come from [DashboardViewModel.gForceHistory] (30 s, ~25 Hz).
 */
@Composable
fun GForcePlot(
    history: List<GForcePoint>,
    modifier: Modifier = Modifier,
    rangeG: Float = 1.5f,
) {
    val dotColor     = MaterialTheme.colorScheme.primary
    val ringColor    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val midRingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val axisColor    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val labelColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle: TextStyle = MaterialTheme.typography.labelSmall

    val last    = history.lastOrNull()
    val latText = last?.let { "%+.2f".format(it.latG) } ?: "—"
    val longText = last?.let { "%+.2f".format(it.longG) } ?: "—"

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // Header: title + live numeric readout
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("G-Meter", style = MaterialTheme.typography.titleSmall)
                Text(
                    "lat $latText  ·  long $longText g",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
            }

            Spacer(Modifier.height(4.dp))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // plotRadius: leave ~18% of the half-size as margin for axis labels
                // plotRadius leaves enough margin for the corner labels without extra canvas height
                val plotRadius = minOf(cx, cy) * 0.78f

                // Maps g values → canvas pixel coordinates.
                // X: positive latG → right; Y: positive longG → up (smaller canvas y)
                fun gToX(g: Double) = cx + (g / rangeG * plotRadius).toFloat()
                fun gToY(g: Double) = cy - (g / rangeG * plotRadius).toFloat()

                // ── Concentric rings ─────────────────────────────────────────────
                // inner ≈ 0.5 g, middle = 1.0 g, outer = rangeG (boundary)
                val halfG = 0.5f / rangeG   // fraction of plotRadius
                val oneG  = 1.0f / rangeG
                drawCircle(color = ringColor,    radius = plotRadius * halfG, style = Stroke(1f))
                drawCircle(color = midRingColor, radius = plotRadius * oneG,  style = Stroke(1.5f))
                drawCircle(color = axisColor,    radius = plotRadius,          style = Stroke(1.5f))

                // Ring labels at 3 o'clock, staggered above/below the axis so they don't overlap
                val lh = textMeasurer.measure("0.5g", labelStyle).size.height.toFloat()
                drawText(textMeasurer, "0.5g",
                    topLeft = Offset(cx + plotRadius * halfG + 3f, cy - lh - 1f),
                    style = labelStyle.copy(color = labelColor))
                drawText(textMeasurer, "1.0g",
                    topLeft = Offset(cx + plotRadius * oneG  + 3f, cy + 2f),
                    style = labelStyle.copy(color = labelColor))

                // ── Crosshairs ───────────────────────────────────────────────────
                drawLine(axisColor, Offset(cx - plotRadius, cy), Offset(cx + plotRadius, cy), 1f)
                drawLine(axisColor, Offset(cx, cy - plotRadius), Offset(cx, cy + plotRadius), 1f)

                if (history.isEmpty()) return@Canvas

                // ── Fading trail ─────────────────────────────────────────────────
                val n = history.size
                history.forEachIndexed { i, pt ->
                    val ageFrac = (i + 1f) / n          // 0 = oldest, 1 = newest
                    drawCircle(
                        color  = dotColor.copy(alpha = ageFrac * 0.65f),
                        radius = 2f + ageFrac * 5f,
                        center = Offset(gToX(pt.latG), gToY(pt.longG)),
                    )
                }

                // ── Current position dot with glow ───────────────────────────────
                last?.let { pt ->
                    val px = gToX(pt.latG)
                    val py = gToY(pt.longG)
                    drawCircle(color = dotColor.copy(alpha = 0.28f), radius = 16f, center = Offset(px, py))
                    drawCircle(color = dotColor,                     radius =  8f, center = Offset(px, py))
                }
            }
        }
    }
}

private const val G_PLOT_TRAIL_MS = 30_000L  // matches DashboardViewModel.G_HISTORY_WINDOW_MS

// ── Legacy bar meter (kept for potential other uses) ──────────────────────────

/** Horizontal bar — fuel trim sign / single-channel level indicator. */
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
