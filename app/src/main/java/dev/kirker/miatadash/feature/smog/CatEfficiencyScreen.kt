package dev.kirker.miatadash.feature.smog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.Mode06Decoder
import dev.kirker.miatadash.core.obd.Mode06Result
import dev.kirker.miatadash.core.obd.Pid
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cat-efficiency screen.
 *
 * Two views:
 *  - Live O2 sensor traces. A healthy catalyst on a warm engine in closed loop will show a
 *    rapidly oscillating pre-cat O2 (B1S1) and a relatively flat ~0.6-0.8V post-cat (B1S2).
 *    A failing cat will show post-cat tracking pre-cat — that's the visual.
 *  - Mode 06 monitor results. The catalyst test has its own MID (MID 01 on most J1979 vehicles
 *    but Mazda-specific on the NC1; we display all results and let the user inspect).
 */
@HiltViewModel
class CatEfficiencyViewModel @Inject constructor(
    private val repo: TelemetryRepository,
) : ViewModel() {
    data class Sample(val tsMs: Long, val pre: Double?, val post: Double?)
    private val _trace = MutableStateFlow<List<Sample>>(emptyList())
    val trace: StateFlow<List<Sample>> = _trace.asStateFlow()

    private val _mode06 = MutableStateFlow<List<Mode06Result>>(emptyList())
    val mode06: StateFlow<List<Mode06Result>> = _mode06.asStateFlow()

    private var streaming = false

    fun startStreaming() {
        if (streaming) return
        streaming = true
        viewModelScope.launch {
            while (streaming) {
                val pre = repo.probe(Pid.O2_B1S1_VOLT) as? dev.kirker.miatadash.core.obd.PidResponse.Ok
                val post = repo.probe(Pid.O2_B1S2_VOLT) as? dev.kirker.miatadash.core.obd.PidResponse.Ok
                _trace.update { (it + Sample(System.currentTimeMillis(), pre?.value, post?.value)).takeLast(120) }
                delay(200)
            }
        }
    }

    fun stopStreaming() { streaming = false }

    fun runMode06() {
        viewModelScope.launch {
            // Probe the on-board test results. Real-world behaviour varies by ECU; we'll log responses
            // verbatim for the user to inspect, then offer parsed rows where possible.
            val frame = repo.session.sendAndAwait("0600", timeoutMs = 4_000) ?: emptyList()
            _mode06.value = Mode06Decoder.decode(frame)
        }
    }
}

@Composable
fun CatEfficiencyScreen(vm: CatEfficiencyViewModel = hiltViewModel()) {
    val trace by vm.trace.collectAsStateWithLifecycle()
    val mode06 by vm.mode06.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Catalyst Efficiency", style = MaterialTheme.typography.titleLarge)
        Text("Pre-cat (B1S1) should hunt 0.1–0.9V at idle/cruise. Post-cat (B1S2) should sit ~0.6–0.8V on a healthy cat.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 8.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Button(onClick = vm::startStreaming) { Text("Start O2 trace") }
            Button(onClick = vm::stopStreaming, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
            Button(onClick = vm::runMode06, modifier = Modifier.padding(start = 8.dp)) { Text("Run Mode 06") }
        }

        // ── Live current-value readout ────────────────────────────────────────
        val lastSample = trace.lastOrNull()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                "Pre-cat:  ${lastSample?.pre?.let { "%.3f V".format(it) } ?: "—"}",
                style     = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color     = Color(0xFFFF6B6B),
            )
            Text(
                "Post-cat: ${lastSample?.post?.let { "%.3f V".format(it) } ?: "—"}",
                style     = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color     = Color(0xFF7DB6E0),
            )
        }

        // ── O2 trace chart ────────────────────────────────────────────────────
        // Y-axis is fixed 0–1 V so the scale never shifts while you're watching.
        // Reference lines:
        //   solid grey  = 0 / 0.5 / 1.0 V grid
        //   dashed grey = 0.45 V stoichiometric crossover (narrowband O2 transition)
        val textMeasurer = rememberTextMeasurer()
        val axisLabelStyle = MaterialTheme.typography.labelSmall
        val axisColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
        val gridColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)

        Surface(
            modifier       = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape          = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
        ) {
            Canvas(
                Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(12.dp)),
            ) {
                val w    = size.width
                val h    = size.height
                val padL = 36f   // left margin for Y-axis labels
                val padB = 4f
                val plotW = w - padL
                val plotH = h - padB

                // Maps 0..1 V → canvas Y (0V = bottom, 1V = top)
                fun yOf(volts: Float) = plotH - (volts * plotH).coerceIn(0f, plotH)

                // ── Y-axis line ───────────────────────────────────────────────
                drawLine(axisColor, Offset(padL, 0f), Offset(padL, plotH), 1f)

                // ── Grid + labels at 0 V, 0.5 V, 1.0 V ──────────────────────
                val gridPoints = listOf(0f to "0V", 0.5f to ".5V", 1.0f to "1V")
                for ((volts, label) in gridPoints) {
                    val y = yOf(volts)
                    drawLine(gridColor, Offset(padL, y), Offset(w, y), 1f)
                    val measured = textMeasurer.measure(label, axisLabelStyle.copy(color = axisColor))
                    drawText(
                        textMeasurer, label,
                        topLeft = Offset(0f, y - measured.size.height / 2f),
                        style   = axisLabelStyle.copy(color = axisColor),
                    )
                }

                // ── 0.45 V stoichiometric crossover (dashed) ─────────────────
                val y45      = yOf(0.45f)
                val stoichColor = axisColor.copy(alpha = 0.55f)
                val dash     = 10f; val gap = 5f
                var xDash    = padL
                while (xDash < w) {
                    drawLine(stoichColor, Offset(xDash, y45), Offset((xDash + dash).coerceAtMost(w), y45), 1.5f)
                    xDash += dash + gap
                }
                val stoichLabel   = "0.45V stoich"
                val stoichMeasured = textMeasurer.measure(stoichLabel, axisLabelStyle.copy(color = stoichColor))
                drawText(
                    textMeasurer, stoichLabel,
                    topLeft = Offset(padL + 4f, y45 - stoichMeasured.size.height - 2f),
                    style   = axisLabelStyle.copy(color = stoichColor),
                )

                if (trace.size < 2) return@Canvas

                // ── Sensor lines ──────────────────────────────────────────────
                val n = trace.size
                for (i in 1 until n) {
                    val a  = trace[i - 1]; val b = trace[i]
                    val x1 = padL + (i - 1).toFloat() / (n - 1) * plotW
                    val x2 = padL + i.toFloat()       / (n - 1) * plotW
                    if (a.pre != null && b.pre != null) {
                        drawLine(Color(0xFFFF6B6B),
                            Offset(x1, yOf(a.pre.toFloat())),
                            Offset(x2, yOf(b.pre.toFloat())),
                            strokeWidth = 2.5f)
                    }
                    if (a.post != null && b.post != null) {
                        drawLine(Color(0xFF7DB6E0),
                            Offset(x1, yOf(a.post.toFloat())),
                            Offset(x2, yOf(b.post.toFloat())),
                            strokeWidth = 2.5f)
                    }
                }
            }
        }
        Text(
            "● pre-cat (red)   ● post-cat (blue)   —  0.45V stoich",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )

        Text("Mode 06 results", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        if (mode06.isEmpty()) {
            Text("Tap 'Run Mode 06' to query on-board test results.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(mode06) { r ->
                    Text(r.pretty() + "  " + (if (r.isPass()) "PASS" else "FAIL"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
