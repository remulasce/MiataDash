package dev.kirker.miatadash.feature.smog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

        Canvas(Modifier.fillMaxWidth().height(200.dp).padding(vertical = 8.dp)) {
            if (trace.isEmpty()) return@Canvas
            val w = size.width; val h = size.height
            // y-axis: 0..1V
            val n = trace.size
            for (i in 1 until n) {
                val a = trace[i - 1]; val b = trace[i]
                val x1 = (i - 1).toFloat() / (n - 1) * w
                val x2 = i.toFloat() / (n - 1) * w
                if (a.pre != null && b.pre != null) {
                    drawLine(Color(0xFFFF6B6B),
                        Offset(x1, h - (a.pre.toFloat() * h).coerceIn(0f, h)),
                        Offset(x2, h - (b.pre.toFloat() * h).coerceIn(0f, h)),
                        strokeWidth = 2f)
                }
                if (a.post != null && b.post != null) {
                    drawLine(Color(0xFF7DB6E0),
                        Offset(x1, h - (a.post.toFloat() * h).coerceIn(0f, h)),
                        Offset(x2, h - (b.post.toFloat() * h).coerceIn(0f, h)),
                        strokeWidth = 2f)
                }
            }
            // 0.5V reference line
            drawLine(Color.Gray, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)
        }
        Text("● pre-cat   ● post-cat   (red / blue)", style = MaterialTheme.typography.labelMedium)

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
