package dev.kirker.miatadash.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.ObdSession
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import dev.kirker.miatadash.core.telemetry.TelemetrySnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: TelemetryRepository,
) : ViewModel() {
    val snapshot: StateFlow<TelemetrySnapshot> = repo.snapshots
    val phase: StateFlow<ObdSession.Phase> = repo.session.phase
    val rates: StateFlow<Map<String, Double>> = repo.rates
    val pidBurstsEnabled: StateFlow<Boolean> = repo.pidBurstsEnabled

    fun setPidBurstsEnabled(enabled: Boolean) = repo.setPidBurstsEnabled(enabled)

    private val _canSnapshot = kotlinx.coroutines.flow.MutableStateFlow<Map<Int, Map<String, Double>>?>(null)
    val canSnapshot: StateFlow<Map<Int, Map<String, Double>>?> = _canSnapshot

    private val _canSnapshotting = kotlinx.coroutines.flow.MutableStateFlow(false)
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
}
