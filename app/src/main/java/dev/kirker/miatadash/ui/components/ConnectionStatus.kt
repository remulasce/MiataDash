package dev.kirker.miatadash.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.obd.ObdSession
import dev.kirker.miatadash.core.telemetry.TelemetryRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Stable user-facing label for the FSM phase. Collapses the rapid Idle/Querying churn into
 * a single "Connected" string so the chip doesn't flicker. The Connection State diagnostic
 * screen still shows full FSM granularity.
 */
fun ObdSession.Phase.stableLabel(): String = when (this) {
    ObdSession.Phase.Disconnected -> "Disconnected"
    ObdSession.Phase.Failed -> "Failed"
    ObdSession.Phase.Opening,
    ObdSession.Phase.Initializing,
    ObdSession.Phase.Reconnecting -> "Connecting…"
    ObdSession.Phase.Idle,
    ObdSession.Phase.Querying,
    ObdSession.Phase.Monitoring -> "Connected"
}

/** Indicator-dot color matching [stableLabel]. */
fun ObdSession.Phase.dotColor(): Color = when (this) {
    ObdSession.Phase.Idle, ObdSession.Phase.Querying, ObdSession.Phase.Monitoring -> StatusColors.Ready
    ObdSession.Phase.Opening, ObdSession.Phase.Initializing, ObdSession.Phase.Reconnecting -> StatusColors.Warn
    ObdSession.Phase.Failed -> StatusColors.Bad
    ObdSession.Phase.Disconnected -> StatusColors.Unknown
}

/** Standalone ViewModel for any screen that wants to read or control connection state. */
@HiltViewModel
class ConnectionStatusViewModel @Inject constructor(
    private val repo: TelemetryRepository,
) : ViewModel() {
    val phase = repo.session.phase
    fun connect() { viewModelScope.launch { repo.connect() } }
    fun disconnect() { viewModelScope.launch { repo.disconnect() } }
}

/**
 * App-wide connection bar. Renders into Scaffold.topBar so every screen sees the same
 * status indicator and (re)connect affordance — no more "did I connect from the Dashboard
 * tab or am I looking at stale data on the Smog tab" confusion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionTopBar(
    title: String = "MiataDash",
    vm: ConnectionStatusViewModel = hiltViewModel(),
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        actions = {
            ConnectionChip(stateLabel = phase.stableLabel(), dotColor = phase.dotColor())
            when (phase) {
                ObdSession.Phase.Disconnected, ObdSession.Phase.Failed ->
                    TextButton(
                        onClick = vm::connect,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    ) { Text("Connect") }
                ObdSession.Phase.Idle,
                ObdSession.Phase.Querying,
                ObdSession.Phase.Monitoring ->
                    TextButton(
                        onClick = vm::disconnect,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    ) { Text("Disconnect") }
                else -> { /* no action available while transitioning */ }
            }
        }
    )
}
