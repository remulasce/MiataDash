package dev.kirker.miatadash.core.transport

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
 */
@Singleton
class TransportSelector @Inject constructor(
    private val bluetooth: BluetoothTransport,
    private val mock: MockTransport,
    private val replay: ReplayTransport,
) {
    private val _kind = MutableStateFlow(TransportKind.MOCK)
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
