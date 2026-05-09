package dev.kirker.miatadash.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.braking.BrakeEvent
import dev.kirker.miatadash.core.obd.ObdSession
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import dev.kirker.miatadash.core.telemetry.TelemetrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One (latG, longG) snapshot for the G-meter X-Y scatter plot.
 *
 * [latG]  — lateral G, positive = rightward force (turning left).
 * [longG] — longitudinal G, positive = braking / deceleration.
 */
data class GForcePoint(val tsMs: Long, val latG: Double, val longG: Double)

/**
 * One timestamped sample of all four wheel speeds plus the ECU vehicle speed.
 *
 * [vehicleKph] is the speed reported by 0x201 (the ECU's own speedometer signal), used as
 * the reference when plotting per-wheel deltas. Storing it per-sample means the graph
 * correctly reflects what the car was doing at each point in the history window even when
 * vehicle speed changes over time.
 */
data class WheelSpeedSample(
    val tsMs: Long,
    val fl: Double,
    val fr: Double,
    val rl: Double,
    val rr: Double,
    val vehicleKph: Double,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: TelemetryRepository,
) : ViewModel() {
    val snapshot: StateFlow<TelemetrySnapshot> = repo.snapshots
    val phase: StateFlow<ObdSession.Phase> = repo.session.phase
    val rates: StateFlow<Map<String, Double>> = repo.rates
    val pidBurstsEnabled: StateFlow<Boolean> = repo.pidBurstsEnabled

    /** Most-recent-first list of completed hard-braking events from the brake detector. */
    val brakeEvents: StateFlow<List<BrakeEvent>> = repo.brakeDetector.events

    fun setPidBurstsEnabled(enabled: Boolean) = repo.setPidBurstsEnabled(enabled)

    // ── G-force X-Y plot history ──────────────────────────────────────────────

    private val gBuffer = ArrayDeque<GForcePoint>()
    private val _gForceHistory = MutableStateFlow<List<GForcePoint>>(emptyList())
    val gForceHistory: StateFlow<List<GForcePoint>> = _gForceHistory.asStateFlow()

    // ── Wheel speed strip-chart history ──────────────────────────────────────

    private val historyBuffer = ArrayDeque<WheelSpeedSample>()
    private val _wheelSpeedHistory = MutableStateFlow<List<WheelSpeedSample>>(emptyList())
    val wheelSpeedHistory: StateFlow<List<WheelSpeedSample>> = _wheelSpeedHistory.asStateFlow()

    init {
        // ── G-force X-Y history — feeds the G-meter scatter plot ─────────────
        // latGForce updates whenever a wheel-speed frame arrives (~25 Hz fold),
        // longGForce updates with PCM_201 (~10 Hz). We sample at the latG cadence
        // and read longG from the same snapshot (slightly stale but close enough
        // at 10 Hz). Keeps 30 s of points → ~750 entries max.
        viewModelScope.launch {
            snapshot.collect { snap ->
                val latReading = snap.latGForce ?: return@collect
                val ts = latReading.tsMs
                if (gBuffer.lastOrNull()?.tsMs == ts) return@collect
                val latG  = latReading.value
                val longG = snap.longGForce?.value ?: 0.0
                gBuffer.addLast(GForcePoint(ts, latG, longG))
                val cutoff = ts - G_HISTORY_WINDOW_MS
                while (gBuffer.firstOrNull()?.let { it.tsMs < cutoff } == true) gBuffer.removeFirst()
                _gForceHistory.value = gBuffer.toList()
            }
        }

        // ── Wheel-speed strip-chart history ───────────────────────────────────
        // Collect every wheel-speed reading, prune samples older than the graph
        // window, and publish the result. The snapshot fires at ~25 Hz for wheel
        // speeds, so we keep at most ~750 samples for a 30 s window — negligible.
        viewModelScope.launch {
            snapshot.collect { snap ->
                val ws = snap.wheelSpeeds ?: return@collect
                val ts = ws.tsMs
                // Skip if this is a duplicate timestamp (snapshot can tick without wheel update).
                if (historyBuffer.lastOrNull()?.tsMs == ts) return@collect
                val vehicleKph = snap.speedKph?.value ?: 0.0
                historyBuffer.addLast(WheelSpeedSample(ts, ws.value.fl, ws.value.fr, ws.value.rl, ws.value.rr, vehicleKph))
                // Prune samples that have fallen outside the graph window.
                val cutoff = ts - WHEEL_HISTORY_WINDOW_MS
                while (historyBuffer.firstOrNull()?.let { it.tsMs < cutoff } == true) {
                    historyBuffer.removeFirst()
                }
                _wheelSpeedHistory.value = historyBuffer.toList()
            }
        }
    }

    // ── CAN snapshot (Test CAN card) ─────────────────────────────────────────

    private val _canSnapshot = MutableStateFlow<Map<Int, Map<String, Double>>?>(null)
    val canSnapshot: StateFlow<Map<Int, Map<String, Double>>?> = _canSnapshot

    private val _canSnapshotting = MutableStateFlow(false)
    val canSnapshotting: StateFlow<Boolean> = _canSnapshotting

    fun connect() = viewModelScope.launch { repo.connect() }
    fun disconnect() = viewModelScope.launch { repo.disconnect() }

    /** Toggled by the Compose DisposableEffect — only poll while the screen is visible. */
    fun setDashboardActive(active: Boolean) = repo.setDashboardActive(active)

    /**
     * Briefly enters CAN monitor mode and captures one frame per subscribed ID. Useful for
     * validating CAN parsing against polled PID values for the same field (e.g. is coolant
     * from `0x240` consistent with Mode 01 PID `0x05`?).
     */
    fun captureCanSnapshot() {
        if (_canSnapshotting.value) return
        viewModelScope.launch {
            _canSnapshotting.value = true
            try {
                _canSnapshot.value = repo.snapshotCan()
            } finally {
                _canSnapshotting.value = false
            }
        }
    }

    companion object {
        /** How far back the live wheel-speed grid shows. Pass this explicitly to the chart composable. */
        const val WHEEL_HISTORY_WINDOW_MS = 5_000L

        /** How far back the G-meter scatter plot trail extends. */
        const val G_HISTORY_WINDOW_MS = 3_000L
    }
}
