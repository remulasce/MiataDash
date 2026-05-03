package dev.kirker.miatadash.feature.smog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.Pid
import dev.kirker.miatadash.core.obd.PidResponse
import dev.kirker.miatadash.core.obd.ReadinessDecoder
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import dev.kirker.miatadash.ui.components.StatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReadinessViewModel @Inject constructor(
    private val repo: TelemetryRepository,
) : ViewModel() {
    private val _sinceClear = MutableStateFlow<ReadinessDecoder.Readiness?>(null)
    val sinceClear: StateFlow<ReadinessDecoder.Readiness?> = _sinceClear.asStateFlow()
    private val _thisCycle = MutableStateFlow<ReadinessDecoder.Readiness?>(null)
    val thisCycle: StateFlow<ReadinessDecoder.Readiness?> = _thisCycle.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val r1 = repo.probe(Pid.MONITOR_STATUS)
            if (r1 is PidResponse.Ok) _sinceClear.value = ReadinessDecoder.decode(r1.raw)
            val r2 = repo.probe(Pid.MONITOR_THIS_CYC)
            if (r2 is PidResponse.Ok) _thisCycle.value = ReadinessDecoder.decode(r2.raw)
        }
    }
}

@Composable
fun ReadinessScreen(vm: ReadinessViewModel = hiltViewModel()) {
    val sinceClear by vm.sinceClear.collectAsStateWithLifecycle()
    val thisCycle by vm.thisCycle.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Readiness Monitors", style = MaterialTheme.typography.titleLarge)
        Text("Most smog stations require all supported non-continuous monitors to be 'Ready' (allowing 1 'Not Ready' for OBD-II vehicles).",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = vm::refresh, modifier = Modifier.padding(vertical = 8.dp)) { Text("Refresh") }

        Section("Since DTCs cleared", sinceClear)
        Section("This drive cycle", thisCycle)
    }
}

@Composable
private fun Section(title: String, r: ReadinessDecoder.Readiness?) {
    Text(title, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    if (r == null) {
        Text("— no data —", style = MaterialTheme.typography.bodyLarge)
        return
    }
    Text(
        "MIL: ${if (r.milOn) "ON" else "off"}  ·  Stored DTCs: ${r.dtcCount}",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    r.monitors.entries.toList().forEach { (m, status) ->
        val (label, color) = when (status) {
            ReadinessDecoder.Status.Ready -> "Ready" to StatusColors.Ready
            ReadinessDecoder.Status.NotReady -> "Not ready" to StatusColors.Warn
            ReadinessDecoder.Status.NotSupported -> "n/a" to StatusColors.Unknown
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Text("  ${m.label}", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

