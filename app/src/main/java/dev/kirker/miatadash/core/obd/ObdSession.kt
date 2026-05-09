package dev.kirker.miatadash.core.obd

import dev.kirker.miatadash.core.transport.Transport
import dev.kirker.miatadash.core.transport.TransportSelector
import dev.kirker.miatadash.core.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversational state machine over a [Transport].
 *
 * Concurrency model: every byte that goes out the wire passes through [busMutex]. That
 * serializes the PID poller's queries, monitor-mode setup, and monitor teardown so they
 * can't interleave on the adapter — important because in CAN monitor mode any byte
 * received by the adapter exits monitor mode immediately.
 *
 * For raw CAN monitoring, [startMonitor] streams parsed lines via [canLines] until
 * [stopMonitor] is called. The monitor flag is checked by the reader to route lines.
 */
@Singleton
class ObdSession @Inject constructor(
    private val transports: TransportSelector,
) {
    enum class Phase { Disconnected, Opening, Initializing, Idle, Querying, Monitoring, Reconnecting, Failed }

    private val _phase = MutableStateFlow(Phase.Disconnected)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Every state transition, time-stamped. Used by ConnectionStateScreen. */
    data class Transition(val from: Phase, val to: Phase, val tsMs: Long, val note: String? = null)
    private val _transitions = MutableSharedFlow<Transition>(replay = 50, extraBufferCapacity = 50)
    val transitions: SharedFlow<Transition> = _transitions.asSharedFlow()

    /** Every line in/out, for RawConsoleScreen. */
    data class WireEvent(val direction: Char, val line: String, val tsMs: Long)
    private val _wire = MutableSharedFlow<WireEvent>(replay = 200, extraBufferCapacity = 1000)
    val wire: SharedFlow<WireEvent> = _wire.asSharedFlow()

    /**
     * Live raw CAN frames in monitor mode.
     *
     * `BufferOverflow.DROP_OLDEST` is critical here: the wheel-speed bus broadcasts at
     * 100 Hz, and if any subscriber falls behind (Compose recomposition stall, GC pause,
     * etc.) we'd otherwise block the reader job that's pulling bytes off the BT socket —
     * which would freeze every value on the dashboard. Better to drop the oldest unread
     * frame than freeze the world.
     */
    data class CanLine(val line: String, val tsMs: Long)
    private val _canLines = MutableSharedFlow<CanLine>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val canLines: SharedFlow<CanLine> = _canLines.asSharedFlow()

    private val parser = ResponseParser()
    private val replyChannel = Channel<List<String>>(Channel.BUFFERED)

    /** Serializes every byte sent to the adapter. Held across an entire request/response. */
    private val busMutex = Mutex()

    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null

    /** Volatile so the reader thread sees writes from setMonitoring on the writer thread. */
    @Volatile private var monitoring: Boolean = false

    private val transport: Transport get() = transports.active

    suspend fun connect() {
        if (_phase.value == Phase.Idle || _phase.value == Phase.Querying) return

        // Tear down any leftover state from a prior (probably failed) session before opening
        // a new one. Without this, stale poll-loop iterations can be sitting on busMutex during
        // their 2.5s timeouts, which delays the new init's ATZ until the timeout fires —
        // visible to the user as "Connect fails after 3-5 seconds".
        cleanupSession()

        transitionTo(Phase.Opening)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        try {
            transport.open()
            startReader(s)
            transitionTo(Phase.Initializing)
            for (cmd in ElmCommands.InitSequence) {
                val resp = sendAndAwait(cmd, timeoutMs = 4_000)
                if (resp == null) {
                    transitionTo(Phase.Failed, "Init timeout on $cmd")
                    return
                }
            }
            transitionTo(Phase.Idle)
        } catch (e: Throwable) {
            Timber.w(e, "connect failed")
            transitionTo(Phase.Failed, e.message)
        }
    }

    /**
     * Cancels reader/scope and resets parser/channel/monitoring state. Used by [connect] to
     * avoid old jobs interfering with a new session, and by [disconnect] for full teardown.
     *
     * Order matters: cancel the job first to send the cancellation signal, then close the
     * transport so any thread blocked on [InputStream.read] receives an [IOException] and
     * returns. Without closing the socket first, [Job.join] waits forever because a blocked
     * native read does not respond to coroutine cancellation alone. This is the root cause of
     * the "disconnect button does nothing" symptom when another app (e.g. Torque Pro) steals
     * the adapter and leaves our socket in a zombie state.
     */
    private suspend fun cleanupSession() {
        readerJob?.cancel()
        runCatching { transport.close() }   // unblocks any hanging stream.read()
        readerJob?.join()                   // now safe — IOException already fired
        readerJob = null
        scope?.cancel()
        scope = null
        parser.reset()
        monitoring = false
        while (replyChannel.tryReceive().isSuccess) { /* drain */ }
    }

    suspend fun disconnect() {
        cleanupSession()
        transport.close()
        transitionTo(Phase.Disconnected)
    }

    /**
     * Sends a command and awaits the next prompt-terminated frame. Holds [busMutex] for the
     * entire duration so other writers can't interleave bytes on the adapter.
     *
     * The whole operation — including lock acquisition, write, and receive — is bounded by
     * `timeoutMs + LOCK_OVERHEAD_MS`. A hung [transport.write] (e.g. dropped Bluetooth socket
     * mid-send) can't keep callers waiting indefinitely; it'll cancel out and queued callers
     * will progress one at a time.
     */
    suspend fun sendAndAwait(
        command: String,
        timeoutMs: Long = 2_500,
        abortIfMonitoring: Boolean = false,
    ): List<String>? = try {
        withTimeoutOrNull(timeoutMs + LOCK_OVERHEAD_MS) {
            busMutex.withLock {
                // If [abortIfMonitoring] is set (PID poll/probes pass true), re-check the
                // monitoring flag *inside* the lock. Without this, a query that passed the
                // outer phase gate could end up writing a byte right after STM engaged,
                // which kicks the adapter back to prompt mode and breaks monitoring.
                if (abortIfMonitoring && monitoring) return@withLock null
                while (replyChannel.tryReceive().isSuccess) { /* drain */ }
                emitWire('W', command)
                transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
                replyChannel.receive()
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Throwable) {
        // Most likely "Transport not open" or an IOException from the BT socket.
        // Reflect that in the FSM so the UI shows Failed instead of crashing the process.
        Timber.w(e, "sendAndAwait failed for $command")
        transitionTo(Phase.Failed, e.message ?: e::class.simpleName)
        null
    }

    suspend fun query(spec: PidSpec, timeoutMs: Long = 100): PidResponse {
        // Skip writing entirely if we're in monitor mode — any byte exits STM.
        if (monitoring) return PidResponse.Garbled(spec.pid, "monitoring")
        transitionTo(Phase.Querying)
        val cmd = "01%02X".format(spec.pid)
        // abortIfMonitoring=true closes the inner race: if monitor mode engaged while we
        // were waiting on busMutex, the inner check bails before we'd otherwise write a
        // PID byte and kick the adapter out of STM.
        val frame = sendAndAwait(cmd, timeoutMs, abortIfMonitoring = true)
            ?: run { transitionTo(Phase.Idle); return PidResponse.NoData(spec.pid) }
        transitionTo(Phase.Idle)
        val line = frame.firstOrNull { it.contains(Regex("[0-9A-Fa-f]")) }
            ?: return PidResponse.Garbled(spec.pid, frame.joinToString())
        return PidDecoder.decode(line, spec.pid)
    }

    /**
     * Reconfigures the adapter for passive CAN monitoring. Holds [busMutex] for the entire
     * setup so concurrent queries can't kick the adapter back to prompt mode.
     */
    suspend fun startMonitor(filterIds: List<Int>) {
        if (monitoring) return
        // Phase guard: refuse if the session isn't actively connected. Without this, tapping
        // Start in CAN Monitor while disconnected would crash with "Transport not open".
        if (_phase.value !in ACTIVE_PHASES) {
            Timber.w("startMonitor refused; session phase is ${_phase.value}")
            return
        }
        try {
            withTimeoutOrNull(MONITOR_SETUP_TIMEOUT_MS) {
                busMutex.withLock {
                    parser.reset()
                    while (replyChannel.tryReceive().isSuccess) { /* drain */ }
                    writeBlocking(ElmCommands.STN_CLEAR_FILTERS)
                    delay(40)
                    for (id in filterIds) {
                        writeBlocking(ElmCommands.stnFilterPass(id))
                        delay(20)
                    }
                    monitoring = true
                    transitionTo(Phase.Monitoring)
                    writeBlocking(ElmCommands.STN_MONITOR)
                }
            } ?: Timber.w("startMonitor timed out — bus may be unresponsive")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Timber.w(e, "startMonitor failed")
            monitoring = false
            transitionTo(Phase.Failed, "monitor setup: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Exits monitor mode. Any byte sent to the adapter while it's streaming exits STM and
     * returns it to the `>` prompt.
     */
    suspend fun stopMonitor() {
        if (!monitoring) return
        try {
            withTimeoutOrNull(MONITOR_SETUP_TIMEOUT_MS) {
                busMutex.withLock {
                    emitWire('W', "<stop>")
                    transport.write("\r".toByteArray())
                    monitoring = false
                    delay(150)
                    parser.reset()
                    while (replyChannel.tryReceive().isSuccess) { /* drain */ }
                    transitionTo(Phase.Idle)
                }
            } ?: run {
                Timber.w("stopMonitor timed out — forcing state reset")
                monitoring = false
                transitionTo(Phase.Idle, "stopMonitor timeout")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            // Best-effort: even if the byte write failed, get the FSM out of Monitoring so
            // the user can re-attempt.
            Timber.w(e, "stopMonitor failed")
            monitoring = false
            transitionTo(Phase.Failed, "stop monitor: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Caller must hold [busMutex]. Writes one command without awaiting a response. */
    private suspend fun writeBlocking(command: String) {
        emitWire('W', command)
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
    }

    private fun startReader(s: CoroutineScope) {
        readerJob = s.launch {
            transport.incoming().collect { bytes ->
                parser.feed(bytes)
                while (true) {
                    val line = parser.takeLine() ?: break
                    emitWire('R', line)
                    // tryEmit is non-suspending; with DROP_OLDEST overflow strategy on
                    // [_canLines] this is safe even at 100 Hz frame rates with slow consumers.
                    if (monitoring) _canLines.tryEmit(CanLine(line, now()))
                }
                while (true) {
                    val frame = parser.takeFrame() ?: break
                    if (!monitoring) replyChannel.trySend(frame)
                }
            }
        }
        // Watch transport state for unexpected closes.
        s.launch {
            transport.state.collect { st ->
                if (st is TransportState.Error || st is TransportState.Closed) {
                    if (_phase.value != Phase.Disconnected) transitionTo(Phase.Reconnecting, "transport ${st::class.simpleName}")
                }
            }
        }
    }

    private fun transitionTo(to: Phase, note: String? = null) {
        val from = _phase.value
        if (from == to && note == null) return
        _phase.value = to
        _transitions.tryEmit(Transition(from, to, now(), note))
    }

    private fun emitWire(dir: Char, line: String) {
        _wire.tryEmit(WireEvent(dir, line, now()))
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        /** Extra budget on top of per-call timeoutMs to cover lock-acquisition delay. */
        const val LOCK_OVERHEAD_MS = 1_000L

        /** Total budget for monitor-mode setup or teardown — short enough to feel responsive. */
        const val MONITOR_SETUP_TIMEOUT_MS = 5_000L

        /** Phases in which the session has a live transport that can accept writes. */
        val ACTIVE_PHASES = setOf(Phase.Idle, Phase.Querying, Phase.Monitoring)
    }
}
