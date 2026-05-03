package dev.kirker.miatadash.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstract byte-level link to an OBD adapter.
 *
 * The whole rest of the app talks to this interface. Three implementations exist:
 *  - [BluetoothTransport] — real RFCOMM SPP to the OBDLink MX+.
 *  - [MockTransport]      — synthesizes plausible ELM327 chatter; default in debug builds.
 *  - [ReplayTransport]    — replays a captured trace file (our `.miatatrace` or Torque Pro CSV).
 *
 * The contract is intentionally minimal: open/close, read incoming bytes, write outgoing bytes.
 * Line buffering, prompt detection, framing — all live one layer up in [dev.kirker.miatadash.core.obd.ResponseParser].
 */
interface Transport {

    val state: StateFlow<TransportState>

    /** Opens the link. Suspends until ready or throws. */
    suspend fun open()

    /** Closes the link. Idempotent. */
    suspend fun close()

    /** Cold flow of inbound bytes. Closes when the transport closes. */
    fun incoming(): Flow<ByteArray>

    /** Sends bytes. Suspends until written (or throws). */
    suspend fun write(bytes: ByteArray)

    /** Friendly identifier for UI ("OBDLink MX+ • 00:1D...", "Mock adapter", "Replay: drive_2026_05_02.miatatrace"). */
    val displayName: String
}

enum class TransportKind { BLUETOOTH, MOCK, REPLAY }

sealed interface TransportState {
    data object Closed : TransportState
    data object Opening : TransportState
    data object Open : TransportState
    data class Error(val cause: Throwable) : TransportState
}
