package dev.kirker.miatadash.feature.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.kirker.miatadash.ui.Routes
import dev.kirker.miatadash.ui.components.ScreenHeader

@Composable
fun DiagnosticsHomeScreen(nav: NavController) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Diagnostics", "Triage tools for when something isn't working")
        DiagItem("Raw Console", "Live ELM tail + send arbitrary commands") { nav.navigate(Routes.DIAG_RAW) }
        DiagItem("PID Explorer", "Probe any PID by hex code, see decoded value") { nav.navigate(Routes.DIAG_PID) }
        DiagItem("CAN Monitor", "Stream raw CAN frames with filters") { nav.navigate(Routes.DIAG_CAN) }
        DiagItem("Connection State", "FSM transitions, time-stamped") { nav.navigate(Routes.DIAG_STATE) }
        DiagItem("Latency Timeline", "Per-PID round-trip times") { nav.navigate(Routes.DIAG_LATENCY) }
        DiagItem("Trace Capture", "Record / browse session traces") { nav.navigate(Routes.DIAG_TRACE) }
        DiagItem("Brake Log", "Stored hard-braking events with slip analysis") { nav.navigate(Routes.DIAG_BRAKE_LOG) }
        DiagItem("Session Logs", "WARN/ERROR log files — current + 2 previous sessions") { nav.navigate(Routes.DIAG_SESSION_LOG) }
    }
}

@Composable
private fun DiagItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
    }
    HorizontalDivider()
}
