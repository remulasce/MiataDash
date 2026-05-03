package dev.kirker.miatadash.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.transport.BluetoothTransport
import dev.kirker.miatadash.core.transport.KnownAdapters
import dev.kirker.miatadash.core.transport.ReplayTransport
import dev.kirker.miatadash.core.transport.TransportKind
import dev.kirker.miatadash.core.transport.TransportSelector
import dev.kirker.miatadash.ui.components.ScreenHeader
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val transports: TransportSelector,
    val bluetooth: BluetoothTransport,
    val replay: ReplayTransport,
) : ViewModel() {
    fun autoDetectAdapter(): Boolean = bluetooth.autoSelectFromPaired()
    fun autoSelectReplay(): Boolean = replay.autoSelectLatest()
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val kind by vm.transports.kind.collectAsStateWithLifecycle()
    var deviceLabel by remember { mutableStateOf(vm.bluetooth.displayName) }
    var replayLabel by remember { mutableStateOf(vm.replay.displayName) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-attempt auto-detect once the user grants permission.
        if (vm.autoDetectAdapter()) deviceLabel = vm.bluetooth.displayName
    }

    // Auto-detect on first composition if Bluetooth transport is the active selection.
    // Auto-select latest trace if Replay is the active selection.
    LaunchedEffect(kind) {
        when (kind) {
            TransportKind.BLUETOOTH -> {
                if (hasBluetoothConnect(ctx)) {
                    if (vm.autoDetectAdapter()) deviceLabel = vm.bluetooth.displayName
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permLauncher.launch(arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                    ))
                }
            }
            TransportKind.REPLAY -> {
                vm.autoSelectReplay()
                replayLabel = vm.replay.displayName
            }
            TransportKind.MOCK -> { /* no setup */ }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", "Transport selection, units, calibration")

        Text("Active transport", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
        TransportKind.values().forEach { tk ->
            // Modifier.selectable makes the entire row a single tap target with the right
            // a11y role; RadioButton becomes a passive indicator (onClick = null).
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = kind == tk,
                        onClick = { vm.transports.select(tk) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = kind == tk, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(tk.label(), style = MaterialTheme.typography.bodyLarge)
                    val sublabel = when (tk) {
                        TransportKind.BLUETOOTH -> deviceLabel
                        TransportKind.REPLAY -> replayLabel
                        TransportKind.MOCK -> null
                    }
                    if (sublabel != null) {
                        Text(
                            sublabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }

        if (kind == TransportKind.BLUETOOTH) {
            Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)) {
                Button(onClick = {
                    val ok = vm.autoDetectAdapter()
                    deviceLabel = if (ok) vm.bluetooth.displayName
                        else "No paired adapter found (looking for ${KnownAdapters.MX_PLUS.label})"
                }) { Text("Re-detect adapter") }
            }
            Text(
                "Looking for: ${KnownAdapters.MX_PLUS.label} • ${KnownAdapters.MX_PLUS.address}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
        }

        if (kind == TransportKind.REPLAY) {
            Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)) {
                Button(onClick = {
                    val ok = vm.autoSelectReplay()
                    replayLabel = if (ok) vm.replay.displayName
                        else "No traces in ${vm.replay.tracesDir.absolutePath}"
                }) { Text("Re-scan traces") }
            }
            Text(
                "Pick a specific trace from Diagnostics → Trace Capture, or push one via:\n" +
                    "adb push <file>.miatatrace ${vm.replay.tracesDir.absolutePath}/",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text("Units, calibration, theme: TODO — wired in via DataStore in a follow-up.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(16.dp))
    }
}

private fun TransportKind.label(): String = when (this) {
    TransportKind.MOCK -> "Mock adapter (synthesized data)"
    TransportKind.BLUETOOTH -> "Bluetooth (OBDLink MX+)"
    TransportKind.REPLAY -> "Replay (file playback)"
}

private fun hasBluetoothConnect(ctx: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else true
}
