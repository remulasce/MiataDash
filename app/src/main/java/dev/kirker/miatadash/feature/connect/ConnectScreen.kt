package dev.kirker.miatadash.feature.connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kirker.miatadash.core.transport.BluetoothTransport
import dev.kirker.miatadash.core.transport.TransportKind
import dev.kirker.miatadash.core.transport.TransportSelector
import dev.kirker.miatadash.ui.components.ScreenHeader
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    val transports: TransportSelector,
) : ViewModel() {
    fun selectMock() = transports.select(TransportKind.MOCK)
    fun selectReplay() = transports.select(TransportKind.REPLAY)
    fun selectBluetooth(device: BluetoothDevice) {
        transports.bluetooth().setDevice(device)
        transports.select(TransportKind.BLUETOOTH)
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(
    onDone: () -> Unit,
    vm: ConnectViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val kind by vm.transports.kind.collectAsState()

    var hasBtPerm by remember { mutableStateOf(hasBluetoothConnect(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasBtPerm = results[Manifest.permission.BLUETOOTH_CONNECT] == true
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPerm) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
    }

    val pairedDevices: List<BluetoothDevice> = remember(hasBtPerm) {
        if (!hasBtPerm) emptyList()
        else runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
            adapter?.bondedDevices?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Connect", "Pick where the app gets data from")

        // Mock and Replay are always available
        RadioRow("Mock adapter", "Synthesized data; no hardware needed", kind == TransportKind.MOCK) {
            vm.selectMock()
        }
        RadioRow("Replay file", "Plays back a saved trace from Documents/MiataDash/traces/", kind == TransportKind.REPLAY) {
            vm.selectReplay()
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Paired Bluetooth devices",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        if (pairedDevices.isEmpty()) {
            Text(
                "No paired devices found. Pair the OBDLink MX+ via Android Settings → Bluetooth (PIN 1234) and come back.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(pairedDevices, key = { it.address }) { device ->
                    val name = runCatching { device.name }.getOrNull() ?: "Unknown"
                    RadioRow(name, device.address, false) { vm.selectBluetooth(device) }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
    }
}

private fun hasBluetoothConnect(ctx: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else true
}

