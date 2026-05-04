package dev.kirker.miatadash.core.transport

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the currently-active [Transport] and lets the user (via Settings) flip between them.
 *
 * Hilt injects all three implementations; we wire one-of through this selector so the
 * downstream layers (ObdSession, TelemetryRepository) are blind to which is active.
 *
 * Default transport: BLUETOOTH on physical devices (where a paired OBDLink MX+ is expected),
 * MOCK in the Android emulator (so engineers can develop/demo without hardware).
 */
@Singleton
class TransportSelector @Inject constructor(
    private val bluetooth: BluetoothTransport,
    private val mock: MockTransport,
    private val replay: ReplayTransport,
) {
    private val _kind = MutableStateFlow(
        if (isEmulator()) TransportKind.MOCK else TransportKind.BLUETOOTH,
    )
    val kind: StateFlow<TransportKind> = _kind.asStateFlow()

    val active: Transport
        get() = when (_kind.value) {
            TransportKind.BLUETOOTH -> bluetooth
            TransportKind.MOCK -> mock
            TransportKind.REPLAY -> replay
        }

    fun bluetooth(): BluetoothTransport = bluetooth
    fun mock(): MockTransport = mock
    fun replay(): ReplayTransport = replay

    fun select(kind: TransportKind) {
        _kind.value = kind
    }
}

/**
 * Heuristic emulator detection. Checks several [Build] fields that are always set to
 * well-known strings on AOSP emulator images (AVD, Genymotion, etc.) but are device-specific
 * strings on real hardware.
 *
 * This is not foolproof — some CI devices surface similar strings — but it's accurate enough
 * for defaulting the transport without requiring user interaction.
 */
private fun isEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
    Build.FINGERPRINT.startsWith("unknown") ||
    Build.MODEL.contains("Emulator", ignoreCase = true) ||
    Build.MODEL.contains("Android SDK built for x86", ignoreCase = true) ||
    Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
    (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
    Build.PRODUCT == "google_sdk" ||
    Build.HARDWARE == "ranchu"
