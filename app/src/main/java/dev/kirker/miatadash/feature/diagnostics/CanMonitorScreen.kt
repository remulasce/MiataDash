package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class CanMonitorViewModel @Inject constructor(
    private val session: ObdSession,
) : ViewModel() {

    /** A raw frame row for the scrolling log view. */
    data class CanLogEntry(val frame: CanFrame, val knownAs: String?)

    /**
     * One entry in the ID histogram. [hz] is derived rather than stored — compute it from
     * [count] and the elapsed time between [firstTsMs] and [latestTsMs].
     *
     * [knownAs] is null when the ID doesn't appear in [MazdaNcDbc] — these are highlighted
     * in the UI to aid discovery of unknown modules (e.g. the SRS accelerometer).
     */
    data class HistogramEntry(
        val id: Int,
        val count: Long,
        val firstTsMs: Long,
        val latestTsMs: Long,
        val knownAs: String?,
    ) {
        /** Frames/second over the observed window. Returns 0 when elapsed < 500 ms. */
        val hz: Double
            get() {
                val elapsedS = (latestTsMs - firstTsMs) / 1000.0
                return if (elapsedS >= 0.5) count / elapsedS else 0.0
            }
    }

    private val _rows = MutableStateFlow<List<CanLogEntry>>(emptyList())
    val rows: StateFlow<List<CanLogEntry>> = _rows.asStateFlow()

    /** All IDs seen since last clearHistogram(), sorted by frame count descending. */
    private val _histogram = MutableStateFlow<List<HistogramEntry>>(emptyList())
    val histogram: StateFlow<List<HistogramEntry>> = _histogram.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Whether the CAN Monitor screen is currently in the foreground. The log-row accumulator
     * respects this flag to avoid allocating at 100 Hz forever after first visit. The histogram
     * accumulator always runs so it captures every frame regardless.
     */
    private val _screenVisible = MutableStateFlow(false)
    fun setScreenVisible(visible: Boolean) { _screenVisible.value = visible }

    // Internal mutable map — updated in the coroutine (single writer), snapshot to StateFlow.
    private val histMap = mutableMapOf<Int, HistogramEntry>()

    init {
        viewModelScope.launch {
            session.canLines.collect { line ->
                val f = CanFrameParser.parse(line.line, line.tsMs) ?: return@collect
                val known = MazdaNcDbc.BY_ID[f.id]?.name

                // ── Histogram: always update ──────────────────────────────────────
                val prev = histMap[f.id]
                histMap[f.id] = if (prev == null) {
                    HistogramEntry(f.id, 1L, line.tsMs, line.tsMs, known)
                } else {
                    prev.copy(count = prev.count + 1, latestTsMs = line.tsMs)
                }
                // Publish sorted snapshot (count desc)
                _histogram.value = histMap.values.sortedByDescending { it.count }

                // ── Log rows: only when screen is visible ─────────────────────────
                if (!_screenVisible.value) return@collect
                _rows.update { (it + CanLogEntry(f, known)).takeLast(MAX_ROWS) }
            }
        }
    }

    fun start(filtersHex: String) {
        val ids = filtersHex.split(Regex("[,\\s]+"))
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull(16) }
        viewModelScope.launch {
            runCatching { session.startMonitor(ids) }
            _running.value = session.phase.value == ObdSession.Phase.Monitoring
        }
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { session.stopMonitor() }
            _running.value = session.phase.value == ObdSession.Phase.Monitoring
        }
    }

    fun clearLog() { _rows.value = emptyList() }

    fun clearHistogram() {
        histMap.clear()
        _histogram.value = emptyList()
    }

    private companion object {
        const val MAX_ROWS = 200
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private enum class CanViewMode { LOG, HISTOGRAM }

@Composable
fun CanMonitorScreen(vm: CanMonitorViewModel = hiltViewModel()) {
    val rows      by vm.rows.collectAsStateWithLifecycle()
    val histogram by vm.histogram.collectAsStateWithLifecycle()
    val running   by vm.running.collectAsStateWithLifecycle()
    var filters   by remember { mutableStateOf("4B0, 081, 085, 090, 201, 240, 231") }
    var viewMode  by remember { mutableStateOf(CanViewMode.LOG) }

    DisposableEffect(Unit) {
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

        // Controls row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!running) Button(onClick = { vm.start(filters) }) { Text("Start") }
            else          Button(onClick = vm::stop)               { Text("Stop") }
            Button(onClick = {
                if (viewMode == CanViewMode.LOG) vm.clearLog() else vm.clearHistogram()
            }) { Text("Clear") }
            Text(
                if (running) "Streaming…" else "Idle",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // Mode selector chips
        Row(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = viewMode == CanViewMode.LOG,
                onClick  = { viewMode = CanViewMode.LOG },
                label    = { Text("Log") },
            )
            FilterChip(
                selected = viewMode == CanViewMode.HISTOGRAM,
                onClick  = { viewMode = CanViewMode.HISTOGRAM },
                label    = { Text("Histogram") },
            )
            if (viewMode == CanViewMode.HISTOGRAM && histogram.isNotEmpty()) {
                Text(
                    "${histogram.size} IDs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }

        when (viewMode) {
            CanViewMode.LOG       -> LogView(rows)
            CanViewMode.HISTOGRAM -> HistogramView(histogram)
        }
    }
}

// ── Log view (original scrolling frame list) ──────────────────────────────────

@Composable
private fun ColumnScope.LogView(rows: List<CanMonitorViewModel.CanLogEntry>) {
    LazyColumn(
        Modifier.weight(1f).padding(top = 8.dp).fillMaxWidth(),
    ) {
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

// ── Histogram view ─────────────────────────────────────────────────────────────────────────

/**
 * Shows one row per unique CAN ID observed since the last Clear. Sorted by frame count
 * (most active first). IDs not found in [MazdaNcDbc] are highlighted in amber — these
 * are candidates for previously-unknown modules such as the SRS accelerometer.
 *
 * Typical usage: start with an empty filter (pass all IDs), let it run for 30 seconds,
 * then scan the amber rows for IDs broadcasting at plausible Hz rates (1–100 Hz).
 */
@Composable
private fun ColumnScope.HistogramView(entries: List<CanMonitorViewModel.HistogramEntry>) {
    if (entries.isEmpty()) {
        Text(
            "No frames yet. Start the monitor and let it run.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 16.dp),
        )
        return
    }

    // Header
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ID",     Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text("Hz",     Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text("Count",  Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text("Signal / Module", Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
    }

    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(entries, key = { it.id }) { entry ->
            val isUnknown = entry.knownAs == null
            val bgTint    = if (isUnknown) Color(0xFFE5A100).copy(alpha = 0.12f) else Color.Transparent
            val hzStr     = if (entry.hz > 0) "%.0f".format(entry.hz) else "—"

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(bgTint, RoundedCornerShape(4.dp))
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "0x%03X".format(entry.id),
                        Modifier.weight(0.8f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUnknown) Color(0xFFE5A100) else MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        hzStr,
                        Modifier.weight(0.7f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "%,d".format(entry.count),
                        Modifier.weight(0.8f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        entry.knownAs ?: "⚠ unknown",
                        Modifier.weight(2f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUnknown)
                            Color(0xFFE5A100)
                        else
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
