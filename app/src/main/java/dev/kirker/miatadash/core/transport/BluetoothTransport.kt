package dev.kirker.miatadash.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RFCOMM SPP transport over Bluetooth Classic.
 *
 * Connect flow:
 *   1. Caller picks a paired BluetoothDevice via [setDevice] (typically from the connect screen).
 *   2. [open] creates an RFCOMM socket on the SPP UUID and connects.
 *   3. Inbound bytes flow on a background thread; [write] is a suspending function that hops to IO.
 *
 * The OBDLink MX+ supports Classic SPP UUID 00001101-0000-1000-8000-00805F9B34FB.
 * Pair the adapter once via system Bluetooth settings, then this transport just connects.
 */
@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Transport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var device: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val displayName: String
        get() = device?.let { "${safeName(it)} • ${it.address}" } ?: "Bluetooth (no device)"

    fun setDevice(device: BluetoothDevice) {
        this.device = device
    }

    /**
     * Walks the system's bonded device list and picks the first one matching a [KnownAdapter]
     * (by MAC, then by name substring). Returns true if a device was selected.
     *
     * Requires `BLUETOOTH_CONNECT` at runtime; returns false if permission isn't granted yet.
     */
    @SuppressLint("MissingPermission")
    fun autoSelectFromPaired(): Boolean {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        // Address match first (deterministic), name match as fallback.
        val byAddress = bonded.firstOrNull { d ->
            KnownAdapters.ALL.any { ka -> d.address.equals(ka.address, ignoreCase = true) }
        }
        val byName = byAddress ?: bonded.firstOrNull { d ->
            val name = runCatching { d.name }.getOrNull() ?: return@firstOrNull false
            KnownAdapters.ALL.any { ka -> name.contains(ka.nameContains, ignoreCase = true) }
        }
        val match = byName ?: return false
        setDevice(match)
        Timber.i("Auto-selected paired adapter: ${runCatching { match.name }.getOrNull()} • ${match.address}")
        return true
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT enforced at UI layer before reaching here
    override suspend fun open() {
        if (device == null) {
            // First-run convenience: try to find a known paired adapter without making the user
            // visit the Connect screen.
            autoSelectFromPaired()
        }
        val dev = device ?: error(
            "No paired OBD adapter found. Pair the OBDLink MX+ via Android Settings (PIN 1234) " +
                "or pick a device on the Connect screen."
        )
        if (_state.value is TransportState.Open) return

        _state.value = TransportState.Opening
        try {
            withContext(Dispatchers.IO) {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
            }
            _state.value = TransportState.Open
            Timber.i("Bluetooth open: $displayName")
        } catch (e: IOException) {
            Timber.w(e, "Bluetooth open failed")
            close()
            _state.value = TransportState.Error(e)
            throw e
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        _state.value = TransportState.Closed
    }

    override fun incoming(): Flow<ByteArray> = callbackFlow {
        val stream = input ?: error("Transport not open")
        val buf = ByteArray(256)
        try {
            while (!isClosedForSend) {
                val n = stream.read(buf)
                if (n <= 0) break
                trySend(buf.copyOf(n))
            }
        } catch (e: IOException) {
            Timber.w(e, "Bluetooth read error")
            _state.value = TransportState.Error(e)
        } finally {
            close(null)
        }
        awaitClose { /* socket close happens in close() */ }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: error("Transport not open")
        out.write(bytes)
        out.flush()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull() ?: "Bluetooth device"

    companion object {
        // Standard Serial Port Profile UUID. The OBDLink MX+ exposes its serial channel here.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
