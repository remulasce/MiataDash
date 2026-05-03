package dev.kirker.miatadash.feature.dashboard

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
import dev.kirker.miatadash.core.units.Units
import dev.kirker.miatadash.ui.components.BarMeter
import dev.kirker.miatadash.ui.components.PrimaryGauge
import dev.kirker.miatadash.ui.components.SecondaryTile

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
    val snap by vm.snapshot.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.setDashboardActive(true)
        onDispose { vm.setDashboardActive(false) }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
    ) {
        // Primary gauges. valueWidth is sized to the max realistic digit count for each so
        // the digits land in a fixed pixel slot (monospace + padStart inside PrimaryGauge).
        PrimaryGauge(
            label = "RPM",
            value = snap.rpm?.value?.toInt()?.toString() ?: "—",
            unit = "rpm",
            modifier = Modifier.fillMaxWidth(),
            valueWidth = 4,    // up to 9999 rpm
        )
        PrimaryGauge(
            label = "SPEED",
            value = snap.speedKph?.value?.let { Units.speed(it).toInt().toString() } ?: "—",
            unit = Units.speedLabel,
            modifier = Modifier.fillMaxWidth(),
            valueWidth = 3,    // up to 999 mph
        )

        // Secondary row 1
        Row(Modifier.fillMaxWidth()) {
            SecondaryTile("COOL", snap.coolantC?.let { "%.0f".format(Units.temp(it.value)) } ?: "—",
                Units.tempLabel, Modifier.weight(1f))
            SecondaryTile("IAT", snap.iatC?.let { "%.0f".format(Units.temp(it.value)) } ?: "—",
                Units.tempLabel, Modifier.weight(1f))
            SecondaryTile("BAT", snap.batteryV?.fmt(2) ?: "—", "V", Modifier.weight(1f))
        }
        // Secondary row 2
        Row(Modifier.fillMaxWidth()) {
            SecondaryTile("THROT", snap.throttlePct?.fmt() ?: "—", "%", Modifier.weight(1f))
            SecondaryTile("LOAD", snap.engineLoadPct?.fmt() ?: "—", "%", Modifier.weight(1f))
            SecondaryTile("MAF", snap.mafGps?.fmt() ?: "—", "g/s", Modifier.weight(1f))
        }
        // Secondary row 3
        Row(Modifier.fillMaxWidth()) {
            SecondaryTile("TIMING", snap.timingDeg?.fmt() ?: "—", "°", Modifier.weight(1f))
            SecondaryTile("STFT", snap.stftPct?.fmt() ?: "—", "%", Modifier.weight(1f))
            SecondaryTile("LTFT", snap.ltftPct?.fmt() ?: "—", "%", Modifier.weight(1f))
        }



        // Wheel speeds (Mazda CAN — populated by the auto-interleave running every ~2s).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Wheel speeds", style = MaterialTheme.typography.titleLarge)
            // Clutch (CAN 0x231 byte 1 MSB on MT). Only shown when we have a reading.
            snap.clutchSwitch?.value?.let { pressed ->
                Text(
                    "clutch: ${if (pressed) "PRESSED" else "released"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
        val ws = snap.wheelSpeeds?.value
        val maxSpeed = listOfNotNull(ws?.fl, ws?.fr, ws?.rl, ws?.rr).maxOrNull()?.coerceAtLeast(1.0) ?: 200.0
        val speedUnit = Units.speedLabel
        BarMeter("FL", ((ws?.fl ?: 0.0) / maxSpeed).toFloat(), ws?.fl?.let { "%.0f $speedUnit".format(Units.speed(it)) } ?: "—")
        BarMeter("FR", ((ws?.fr ?: 0.0) / maxSpeed).toFloat(), ws?.fr?.let { "%.0f $speedUnit".format(Units.speed(it)) } ?: "—")
        BarMeter("RL", ((ws?.rl ?: 0.0) / maxSpeed).toFloat(), ws?.rl?.let { "%.0f $speedUnit".format(Units.speed(it)) } ?: "—")
        BarMeter("RR", ((ws?.rr ?: 0.0) / maxSpeed).toFloat(), ws?.rr?.let { "%.0f $speedUnit".format(Units.speed(it)) } ?: "—")

        // Live update-rate stats + PID-polling toggle.
        StatsPanel(vm)

        // CAN validation card — briefly enters monitor mode, decodes one frame per subscribed
        // ID, and shows the result alongside polled values for sanity-checking the formulas.
        CanSnapshotCard(vm)
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
