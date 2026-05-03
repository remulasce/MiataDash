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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.can.CanFrame
import dev.kirker.miatadash.core.can.CanFrameParser
import dev.kirker.miatadash.core.can.MazdaNcDbc
import dev.kirker.miatadash.core.obd.ObdSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CanMonitorViewModel @Inject constructor(
    private val session: ObdSession,
) : ViewModel() {
    data class Row(val frame: CanFrame, val knownAs: String?)

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Whether the CAN Monitor screen is currently in the foreground. The collector below
     * uses this to decide whether to actually accumulate rows — when off-screen we still
     * subscribe (so we don't drop the SharedFlow's view of the bus) but we don't allocate
     * new lists, since the user can't see them anyway. Otherwise this VM (which Hilt scopes
     * to the activity, not the screen) would allocate at 100 Hz forever after the user
     * visited this screen once.
     */
    private val _screenVisible = MutableStateFlow(false)
    fun setScreenVisible(visible: Boolean) { _screenVisible.value = visible }

    init {
        viewModelScope.launch {
            session.canLines.collect { line ->
                if (!_screenVisible.value) return@collect
                val f = CanFrameParser.parse(line.line, line.tsMs) ?: return@collect
                val known = MazdaNcDbc.BY_ID[f.id]?.name
                _rows.update { (it + Row(f, known)).takeLast(MAX_ROWS) }
            }
        }
    }

    private companion object {
        const val MAX_ROWS = 200   // smaller window — list-copy cost was nontrivial at 100 Hz
    }

    fun start(filtersHex: String) {
        val ids = filtersHex.split(Regex("[,\\s]+"))
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull(16) }
        viewModelScope.launch {
            runCatching { session.startMonitor(ids) }
            // Reflect actual session phase, not just our intent — startMonitor may have
            // refused (disconnected) or failed (transport error).
            _running.value = session.phase.value == ObdSession.Phase.Monitoring
        }
    }
    fun stop() {
        viewModelScope.launch {
            runCatching { session.stopMonitor() }
            _running.value = session.phase.value == ObdSession.Phase.Monitoring
        }
    }
    fun clear() { _rows.value = emptyList() }
}

@Composable
fun CanMonitorScreen(vm: CanMonitorViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf("4B0, 081, 085, 201, 240, 231") }

    // Tell the VM when this screen is visible so its accumulator only does work when it can
    // actually be seen. Without this, the VM allocates row lists at 100 Hz forever after the
    // user has visited this screen once.
    androidx.compose.runtime.DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("CAN Monitor", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = filters, onValueChange = { filters = it },
            label = { Text("Filter IDs (hex, comma-separated). Empty = pass all") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!running) Button(onClick = { vm.start(filters) }) { Text("Start") }
            else Button(onClick = vm::stop) { Text("Stop") }
            Button(onClick = vm::clear) { Text("Clear") }
            Text(if (running) "Streaming…" else "Idle", style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp).fillMaxWidth()) {
            items(rows) { r ->
                val hex = r.frame.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Text(
                    "%03X  %s  %s".format(r.frame.id, hex.padEnd(24), r.knownAs ?: ""),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
