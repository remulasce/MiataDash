package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.Pid
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LatencyViewModel @Inject constructor(
    repo: TelemetryRepository,
) : ViewModel() {
    private val _samples = MutableStateFlow<List<TelemetryRepository.LatencySample>>(emptyList())
    val samples: StateFlow<List<TelemetryRepository.LatencySample>> = _samples.asStateFlow()

    init {
        viewModelScope.launch {
            repo.latency.collect { s ->
                val cutoff = System.currentTimeMillis() - 60_000
                _samples.update { (it + s).filter { x -> x.tsMs >= cutoff } }
            }
        }
    }
}

@Composable
fun LatencyScreen(vm: LatencyViewModel = hiltViewModel()) {
    val samples by vm.samples.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary  // hoist out of DrawScope
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Latency Timeline (last 60s)", style = MaterialTheme.typography.titleLarge)
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 12.dp)) {
            if (samples.isEmpty()) return@Canvas
            val now = System.currentTimeMillis()
            val maxRtt = samples.maxOf { it.rttMs }.coerceAtLeast(50L)
            val w = size.width; val h = size.height
            // Axis
            drawLine(Color.Gray, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
            samples.forEach { s ->
                val x = ((s.tsMs - (now - 60_000)) / 60_000f) * w
                val y = h - (s.rttMs.toFloat() / maxRtt.toFloat()) * h
                drawCircle(accent, radius = 3f, center = Offset(x, y))
            }
        }
        Text("By PID — last 20 samples each", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp))
        val byPid = samples.groupBy { it.pid }.mapValues { it.value.takeLast(20) }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(byPid.entries.toList()) { (pid, list) ->
                val name = Pid.All[pid]?.name ?: "0x%02X".format(pid)
                val avg = list.map { it.rttMs }.average().toInt()
                val mx = list.maxOf { it.rttMs }
                Text("%s — avg ${avg}ms, max ${mx}ms".format(name),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
