package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.Pid
import dev.kirker.miatadash.core.obd.PidResponse
import dev.kirker.miatadash.core.obd.PidSpec
import dev.kirker.miatadash.core.obd.RefreshTier
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PidExplorerViewModel @Inject constructor(
    private val repo: TelemetryRepository,
) : ViewModel() {
    data class Probe(val pid: Int, val name: String, val response: String, val rttMs: Long)

    private val _probes = MutableStateFlow<List<Probe>>(emptyList())
    val probes: StateFlow<List<Probe>> = _probes.asStateFlow()

    fun probe(pidHex: String) {
        val pid = pidHex.trim().toIntOrNull(16) ?: return
        val spec = Pid.All[pid] ?: PidSpec(pid, "PID 0x%02X".format(pid), "", 0, { 0.0 }, RefreshTier.Slow)
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val r = repo.probe(spec)
            val rtt = System.currentTimeMillis() - t0
            val str = when (r) {
                is PidResponse.Ok -> "${"%.2f".format(r.value)} ${r.unit}  (raw: ${r.raw.toHex()})"
                is PidResponse.NoData -> "NO DATA"
                is PidResponse.Unsupported -> "UNSUPPORTED"
                is PidResponse.Garbled -> "GARBLED: ${r.rawLine}"
            }
            _probes.update { (listOf(Probe(pid, spec.name, str, rtt)) + it).take(50) }
        }
    }
}

@Composable
fun PidExplorerScreen(vm: PidExplorerViewModel = hiltViewModel()) {
    val probes by vm.probes.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("0C") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("PID Explorer", style = MaterialTheme.typography.titleLarge)
        Text("Enter a Mode 01 PID in hex (e.g. 0C for RPM, 05 for coolant)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(2) },
                label = { Text("PID hex") },
                singleLine = true,
            )
            Button(onClick = { vm.probe(input) }, modifier = Modifier.padding(start = 12.dp)) {
                Text("Probe")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("0C", "0D", "05", "11", "10", "42").forEach { hx ->
                Button(onClick = { input = hx; vm.probe(hx) }) { Text(hx) }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
            items(probes) { p ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("0x%02X — ${p.name}".format(p.pid), style = MaterialTheme.typography.titleLarge)
                    Text(p.response, style = MaterialTheme.typography.bodyLarge)
                    Text("rtt ${p.rttMs}ms", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
