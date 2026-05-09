package dev.kirker.miatadash.feature.dashboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kirker.miatadash.core.telemetry.TelemetrySnapshot
import dev.kirker.miatadash.core.units.Units
import dev.kirker.miatadash.ui.components.BrakeReportCard
import dev.kirker.miatadash.ui.components.GForcePlot
import dev.kirker.miatadash.ui.components.PrimaryGauge
import dev.kirker.miatadash.ui.components.SecondaryTile
import dev.kirker.miatadash.ui.components.WheelSpeedCornerGrid
import dev.kirker.miatadash.ui.components.WheelSpeedGraph
import dev.kirker.miatadash.ui.components.coolantTileColor
import dev.kirker.miatadash.ui.components.iatTileColor

/**
 * Live dashboard. The connection chip + Connect/Disconnect buttons live in the global
 * [dev.kirker.miatadash.ui.components.ConnectionTopBar] (visible on every screen), so this
 * screen is just gauges.
 *
 * The poll loop is gated on this screen being in the foreground via the
 * [DisposableEffect] below — when the user navigates to Smog/Diag/Settings, polling
 * pauses so the smog/diag screens' own probes have unobstructed bus access.
 */
@Composable
fun DashboardScreen(
    onConnect: () -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val snap          by vm.snapshot.collectAsStateWithLifecycle()
    val history       by vm.wheelSpeedHistory.collectAsStateWithLifecycle()
    val brakeEvents   by vm.brakeEvents.collectAsStateWithLifecycle()
    val gForceHistory by vm.gForceHistory.collectAsStateWithLifecycle()
    val isDark        = isSystemInDarkTheme()

    DisposableEffect(Unit) {
        vm.setDashboardActive(true)
        onDispose { vm.setDashboardActive(false) }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
    ) {
        // Primary gauges — compact so the wheel-speed graph gets more real estate.
        // valueWidth is sized to the max realistic digit count so digits don't shift.
        Row(Modifier.fillMaxWidth()) {
            PrimaryGauge(
                label = "RPM",
                value = snap.rpm?.value?.toInt()?.toString() ?: "—",
                unit = "rpm",
                modifier = Modifier.weight(1f),
                valueWidth = 4,
                compact = true,
            )
            PrimaryGauge(
                label = "SPEED",
                value = snap.speedKph?.value?.let { Units.speed(it).toInt().toString() } ?: "—",
                unit = Units.speedLabel,
                modifier = Modifier.weight(1f),
                valueWidth = 3,
                compact = true,
            )
        }

        // Temperature row — COOL and IAT are the most important secondary gauges; give them
        // the full 2-column width so they're easy to glance at while driving.
        val coolC = snap.coolantC?.value
        val iatC  = snap.iatC?.value
        Row(Modifier.fillMaxWidth()) {
            SecondaryTile(
                label = "COOLANT",
                value = coolC?.let { "%.0f".format(Units.temp(it)) } ?: "—",
                unit = Units.tempLabel,
                modifier = Modifier.weight(1f),
                containerColor = coolC?.let { coolantTileColor(it, isDark) }
                    ?: MaterialTheme.colorScheme.surface,
            )
            SecondaryTile(
                label = "INTAKE AIR",
                value = iatC?.let { "%.0f".format(Units.temp(it)) } ?: "—",
                unit = Units.tempLabel,
                modifier = Modifier.weight(1f),
                containerColor = iatC?.let { iatTileColor(it, isDark) }
                    ?: MaterialTheme.colorScheme.surface,
            )
        }
        // Engine metrics — 3-column layout (less critical, smaller tiles are fine)
        Row(Modifier.fillMaxWidth()) {
            SecondaryTile("THROTTLE", snap.throttlePct?.fmt() ?: "—", "%",    Modifier.weight(1f))
            SecondaryTile("LOAD",     snap.engineLoadPct?.fmt() ?: "—", "%",  Modifier.weight(1f))
            SecondaryTile("MAF",      snap.mafGps?.fmt() ?: "—",       "g/s", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            SecondaryTile("TIMING",   snap.timingDeg?.fmt() ?: "—", "°",  Modifier.weight(1f))
            SecondaryTile("STFT",     snap.stftPct?.fmt() ?: "—",   "%",  Modifier.weight(1f))
            SecondaryTile("LTFT",     snap.ltftPct?.fmt() ?: "—",   "%",  Modifier.weight(1f))
        }

        // G-meter — visual X-Y scatter plot (lat vs long G) with 30 s trail.
        GForcePlot(history = gForceHistory)

        // SRS calibration tiles — only visible once 0x430 frames start arriving.
        SrsCalibrationRow(snap)

        // Wheel speed strip chart header row (clutch indicator on the right).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Wheel speed deltas", style = MaterialTheme.typography.titleLarge)
            snap.clutchSwitch?.value?.let { pressed ->
                Text(
                    "clutch: ${if (pressed) "PRESSED" else "released"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
        // 2×2 corner grid — one panel per wheel, same Y-scale across all four.
        // Swap to WheelSpeedGraph for the 4-line overlay layout.
        WheelSpeedCornerGrid(
            history   = history,
            windowMs  = DashboardViewModel.WHEEL_HISTORY_WINDOW_MS,
            modifier  = Modifier.padding(horizontal = 4.dp),
        )

        // Braking performance report — populated whenever the detector fires.
        BrakeReportCard(events = brakeEvents)

        // Live update-rate stats + PID-polling toggle.
        StatsPanel(vm)

        // CAN validation card — briefly enters monitor mode, decodes one frame per subscribed
        // ID, and shows the result alongside polled values for sanity-checking the formulas.
        CanSnapshotCard(vm)
    }
}

// ── SRS accelerometer calibration row ────────────────────────────────────────

/**
 * Shows the raw int16 values from the confirmed SRS accelerometer (0x430) as secondary
 * tiles. Only rendered once frames from that ID start arriving.
 *
 * These raw values are used to calibrate the scale factor: divide the peak ch2 change
 * during a hard stop by the simultaneously-derived longitudinal G (from the G-meter)
 * to get LSB/g. Once calibrated, a proper g decode can be added to MazdaNcDbc.
 */
@Composable
private fun SrsCalibrationRow(snap: TelemetrySnapshot) {
    val srsRaw0 = snap.srsAccelRaw0?.value ?: return
    val srsRaw2 = snap.srsAccelRaw2?.value
    Row(Modifier.fillMaxWidth()) {
        SecondaryTile(
            label    = "SRS CH0 (0x430)",
            value    = "%.0f".format(srsRaw0),
            unit     = "raw",
            modifier = Modifier.weight(1f),
        )
        SecondaryTile(
            label    = "SRS CH2 (long?)",
            value    = srsRaw2?.let { "%.0f".format(it) } ?: "—",
            unit     = "raw",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CanSnapshotCard(vm: DashboardViewModel) {
    val snapshotting by vm.canSnapshotting.collectAsStateWithLifecycle()
    val canSnap by vm.canSnapshot.collectAsStateWithLifecycle()
    val polled by vm.snapshot.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("CAN test", style = MaterialTheme.typography.titleLarge)
                Button(onClick = vm::captureCanSnapshot, enabled = !snapshotting) {
                    Text(if (snapshotting) "Capturing…" else "Test CAN")
                }
            }
            Text(
                "Briefly enters monitor mode, decodes 0x240 / 0x4B0 / etc. and shows them next to the polled PID values. Lets you sanity-check the CAN formulas while parked.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (canSnap == null) {
                Text("No snapshot yet.", style = MaterialTheme.typography.bodyLarge)
            } else {
                val frame240 = canSnap?.get(0x240)
                val frame201 = canSnap?.get(0x201)
                val frame4B0 = canSnap?.get(0x4B0)

                ComparisonRow(
                    label = "Coolant",
                    canValue = frame240?.get("coolant_c")?.let { "%.0f °C / %.0f °F".format(it, it * 9 / 5 + 32) },
                    pidValue = polled.coolantC?.value?.let { "%.0f °C".format(it) },
                )
                ComparisonRow(
                    label = "IAT",
                    canValue = frame240?.get("iat_c")?.let { "%.0f °C".format(it) },
                    pidValue = polled.iatC?.value?.let { "%.0f °C".format(it) },
                )
                ComparisonRow(
                    label = "Engine load",
                    canValue = frame240?.get("engine_load_pct")?.let { "%.1f%%".format(it) },
                    pidValue = polled.engineLoadPct?.value?.let { "%.1f%%".format(it) },
                )
                ComparisonRow(
                    label = "Throttle (CAN/PID)",
                    canValue = frame240?.get("throttle_valve_pct")?.let { "%.1f%%".format(it) },
                    pidValue = polled.throttlePct?.value?.let { "%.1f%%".format(it) },
                )
                ComparisonRow(
                    label = "RPM",
                    canValue = frame201?.get("rpm")?.let { "%.0f".format(it) },
                    pidValue = polled.rpm?.value?.let { "%.0f".format(it) },
                )
                ComparisonRow(
                    label = "Wheel FL kph",
                    canValue = frame4B0?.get("fl_kph")?.let { "%.1f".format(it) },
                    pidValue = null,    // no PID equivalent
                )

                Text(
                    "Frames seen: ${canSnap?.keys?.joinToString { "0x%03X".format(it) } ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, canValue: String?, pidValue: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            "CAN: ${canValue ?: "—"}",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            "PID: ${pidValue ?: "—"}",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun dev.kirker.miatadash.core.telemetry.Reading<Double>.fmt(decimals: Int = 0): String =
    "%.${decimals}f".format(value)

/**
 * Live event rates per data source, plus a switch to disable PID polling entirely.
 *
 * Rates are events/second over a 5-second window:
 *  - "can_4B0" etc. — raw CAN frame arrivals on the bus (pre-throttle, what the bus is
 *    actually broadcasting). UI sees a throttled view at ~10 Hz max regardless.
 *  - "pid_10" etc. — successful PID responses during the periodic burst. Each PID gets
 *    one update per burst, so at 5 PIDs every 5 seconds you'll see ~0.2 Hz per PID.
 */
@Composable
private fun StatsPanel(vm: DashboardViewModel) {
    val rates by vm.rates.collectAsStateWithLifecycle()
    val pidEnabled by vm.pidBurstsEnabled.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Update rates", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PID polling",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = pidEnabled,
                        onCheckedChange = { vm.setPidBurstsEnabled(it) },
                    )
                }
            }
            Text(
                if (pidEnabled) "CAN-default + 5s PID burst (MAF, battery, fuel trims, timing)."
                else "CAN-only — PID-only fields (MAF/battery/STFT/LTFT/timing) are frozen.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            val canKeys = rates.keys.filter { it.startsWith("can_") }.sorted()
            val pidKeys = rates.keys.filter { it.startsWith("pid_") }.sorted()

            if (canKeys.isNotEmpty()) {
                Text(
                    "CAN frames",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                canKeys.forEach { key ->
                    val id = key.removePrefix("can_")
                    val rate = rates[key] ?: 0.0
                    val label = MAZDA_NAMES[id] ?: ""
                    RateRow("0x$id $label", rate, decimals = 1)
                }
            }
            if (pidKeys.isNotEmpty()) {
                Text(
                    "PID polls",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                pidKeys.forEach { key ->
                    val pid = key.removePrefix("pid_")
                    val rate = rates[key] ?: 0.0
                    val label = PID_NAMES[pid] ?: ""
                    RateRow("0x$pid $label", rate, decimals = 2)
                }
            }
            if (canKeys.isEmpty() && pidKeys.isEmpty()) {
                Text(
                    "No data yet — connect to start streaming.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun RateRow(label: String, rate: Double, decimals: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "%.${decimals}f Hz".format(rate),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private val MAZDA_NAMES = mapOf(
    "4B0" to "wheel_speeds",
    "201" to "pcm",
    "240" to "engine",
    "231" to "trans",
    "081" to "steering",
    "085" to "brake",
    "215" to "throttle_alt",
)

private val PID_NAMES = mapOf(
    "10" to "MAF",
    "42" to "battery",
    "06" to "STFT",
    "07" to "LTFT",
    "0E" to "timing",
)
