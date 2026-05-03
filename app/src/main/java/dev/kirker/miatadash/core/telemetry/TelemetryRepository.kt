package dev.kirker.miatadash.core.telemetry

import dev.kirker.miatadash.core.can.CanFrame
import dev.kirker.miatadash.core.can.CanFrameParser
import dev.kirker.miatadash.core.can.MazdaNcDbc
import dev.kirker.miatadash.core.obd.ObdSession
import dev.kirker.miatadash.core.obd.Pid
import dev.kirker.miatadash.core.obd.PidResponse
import dev.kirker.miatadash.core.obd.PidSpec
import dev.kirker.miatadash.core.transport.TransportKind
import dev.kirker.miatadash.core.transport.TransportSelector
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin

/**
 * Coordinates the OBD session, the poll scheduler, and the telemetry snapshot.
 *
 * - Connects through the active [dev.kirker.miatadash.core.transport.Transport].
 * - Round-robins PIDs through [ObdSession.query] at tier rates.
 * - Folds responses into the [snapshots] state.
 *
 * The UI observes [snapshots] only. Every other surface (raw wire, FSM transitions, latency)
 * is exposed by [ObdSession] for the diagnostic screens.
 */
@Singleton
class TelemetryRepository @Inject constructor(
    val session: ObdSession,
    private val transports: TransportSelector,
) {
    private val _snapshots = MutableStateFlow(TelemetrySnapshot())
    val snapshots: StateFlow<TelemetrySnapshot> = _snapshots.asStateFlow()

    /** Per-PID round-trip time in millis. Populated by query loop; consumed by Latency screen. */
    data class LatencySample(val pid: Int, val rttMs: Long, val tsMs: Long)
    private val _latency = MutableSharedFlow<LatencySample>(replay = 0, extraBufferCapacity = 256)
    val latency: SharedFlow<LatencySample> = _latency.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var canFoldJob: Job? = null
    private var ratesJob: Job? = null
    private var mockSynthJob: Job? = null

    /**
     * Per-frame-ID throttle: the CAN bus broadcasts certain frames at 100 Hz which would
     * thrash Compose recomposition if we updated the snapshot every time. We cap each ID's
     * snapshot updates to roughly [CAN_FOLD_MIN_INTERVAL_MS] so the UI sees ~10 Hz.
     */
    private val canFoldLastTsMs = mutableMapOf<Int, Long>()

    /** Per-source event-rate tracker for the dashboard's stats panel. */
    private val rateTracker = RateTracker()
    private val _rates = MutableStateFlow<Map<String, Double>>(emptyMap())
    val rates: StateFlow<Map<String, Double>> = _rates.asStateFlow()

    /**
     * When false, the dashboard's poll loop skips the periodic PID burst and stays in CAN
     * monitor mode permanently. PID-only fields (MAF, battery, fuel trims, timing) freeze
     * at whatever value they last had. Useful when the user wants the absolute fastest CAN
     * update rates and doesn't care about those slow fields.
     */
    private val _pidBurstsEnabled = MutableStateFlow(false)
    val pidBurstsEnabled: StateFlow<Boolean> = _pidBurstsEnabled.asStateFlow()
    fun setPidBurstsEnabled(enabled: Boolean) { _pidBurstsEnabled.value = enabled }

    /**
     * Whether the Dashboard screen is currently in the user's foreground. Toggled by
     * [DashboardScreen]'s DisposableEffect. The poll loop suspends while this is false so
     * smog/diagnostic screens can issue probes without competing with dashboard polling.
     */
    private val _dashboardActive = MutableStateFlow(false)

    fun setDashboardActive(active: Boolean) { _dashboardActive.value = active }

    suspend fun connect() {
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session.connect()
        startCanFold()
        startPolling()
        startRatesEmitter()
        startMockSynthIfNeeded()
    }

    suspend fun disconnect() {
        pollJob?.cancel(); pollJob = null
        canFoldJob?.cancel(); canFoldJob = null
        ratesJob?.cancel(); ratesJob = null
        mockSynthJob?.cancel(); mockSynthJob = null
        scope?.cancel(); scope = null
        session.disconnect()
        canFoldLastTsMs.clear()
        rateTracker.reset()
        _rates.value = emptyMap()
        _snapshots.value = TelemetrySnapshot()
    }

    /**
     * Briefly enters CAN-monitor mode, captures one frame per filtered ID for [durationMs],
     * exits monitor mode, and returns the decoded signals for each frame.
     *
     * Used by the Dashboard's "Test CAN" affordance to validate that the CAN path is alive
     * and that the [MazdaNcDbc] decoders match the polled-PID values for the same fields
     * (e.g. coolant from `0x240` byte 1 should equal Mode 01 PID 0x05).
     *
     * The poll loop suspends automatically while we're in monitor mode (see [startPolling]).
     */
    suspend fun snapshotCan(
        filterIds: List<Int> = MazdaNcDbc.SubscribedIds,
        durationMs: Long = 1_500,
    ): Map<Int, Map<String, Double>> {
        val collected = mutableMapOf<Int, CanFrame>()
        // Don't toggle monitor mode if it's already running (e.g. the auto-CAN main loop).
        // Just observe canLines for the duration and let the caller compare.
        val weStartedIt = session.phase.value != ObdSession.Phase.Monitoring
        try {
            if (weStartedIt) session.startMonitor(filterIds)
            withTimeoutOrNull(durationMs) {
                session.canLines.collect { line ->
                    CanFrameParser.parse(line.line, line.tsMs)?.let { f ->
                        collected[f.id] = f
                    }
                }
            }
        } finally {
            if (weStartedIt) session.stopMonitor()
        }
        return collected.mapValues { (id, frame) ->
            MazdaNcDbc.BY_ID[id]?.decode?.invoke(frame) ?: emptyMap()
        }
    }

    /** Manually re-trigger a single PID; used by PidExplorerScreen. */
    suspend fun probe(spec: PidSpec): PidResponse {
        val t0 = System.currentTimeMillis()
        val r = session.query(spec)
        val rtt = System.currentTimeMillis() - t0
        _latency.tryEmit(LatencySample(spec.pid, rtt, t0))
        if (r is PidResponse.Ok) fold(r)
        return r
    }

    /**
     * In MOCK mode the adapter doesn't actually broadcast Mazda CAN frames (and even on real
     * hardware those only flow when we put the adapter into STM monitor mode, which conflicts
     * with PID polling). To exercise the dashboard wheel-speed bars without driving anywhere,
     * we synthesize wheel speeds locally from the current speedKph reading whenever MOCK is
     * the active transport. No-op for BLUETOOTH and REPLAY.
     */
    private fun startMockSynthIfNeeded() {
        val s = scope ?: return
        mockSynthJob?.cancel()
        mockSynthJob = s.launch {
            while (true) {
                if (transports.kind.value == TransportKind.MOCK) {
                    val baseKph = _snapshots.value.speedKph?.value ?: 0.0
                    val now = System.currentTimeMillis()
                    // Each wheel jitters slightly off the vehicle speed — front-left lags in a
                    // gentle cornering bias to make the bars visibly different.
                    val phase = now / 800.0
                    val ws = WheelSpeeds(
                        fl = (baseKph * (1 - 0.015 * sin(phase))).coerceAtLeast(0.0),
                        fr = (baseKph * (1 + 0.010 * sin(phase + 0.3))).coerceAtLeast(0.0),
                        rl = (baseKph * (1 - 0.008 * sin(phase + 0.6))).coerceAtLeast(0.0),
                        rr = (baseKph * (1 + 0.012 * sin(phase + 0.9))).coerceAtLeast(0.0),
                    )
                    _snapshots.update { it.copy(tsMs = now, wheelSpeeds = Reading(ws, now)) }
                }
                delay(200)
            }
        }
    }

    /**
     * Dashboard live loop. CAN monitor is the default mode — most fields (RPM, speed, coolant,
     * IAT, throttle, engine load, wheel speeds, clutch, accelerator) are folded continuously
     * from the bus broadcast at 10-100 Hz. Every [PID_BURST_INTERVAL_MS] we briefly drop out
     * of monitor to poll the PID-only fields ([Pid.PidOnlyDashboard]: MAF, battery, fuel
     * trims, timing) and then re-enter monitor.
     *
     * Loop suspends while dashboard is off-screen OR an *external* monitor (CAN Monitor
     * diagnostic screen) is running, so it doesn't fight other consumers for the bus.
     */
    private fun startPolling() {
        val s = scope ?: return
        pollJob?.cancel()
        pollJob = s.launch {
            var lastPidBurstMs = 0L
            try {
                while (true) {
                    // Inner gate. If conditions aren't met, drop our monitor (if we own it)
                    // and wait. We re-check periodically rather than collecting on a flow so
                    // the loop body stays single-threaded.
                    while (true) {
                        val active = _dashboardActive.value
                        val externalMonitor = session.phase.value == ObdSession.Phase.Monitoring &&
                            !weOwnMonitor
                        if (active && !externalMonitor) break
                        if (weOwnMonitor) {
                            session.stopMonitor()
                            weOwnMonitor = false
                        }
                        delay(100)
                    }

                    val now = System.currentTimeMillis()

                    // Time for a PID burst? Also: skip entirely when the user has toggled
                    // PID polling off — they want CAN-only with no monitor interruptions.
                    if (_pidBurstsEnabled.value && now - lastPidBurstMs >= PID_BURST_INTERVAL_MS) {
                        Timber.d("Poll loop: dropping monitor for PID burst")
                        if (weOwnMonitor) {
                            session.stopMonitor()
                            weOwnMonitor = false
                        }
                        for (spec in Pid.PidOnlyDashboard) {
                            val t0 = System.currentTimeMillis()
                            val r = runCatching { session.query(spec) }
                                .onFailure { Timber.w(it, "PID query failed for ${spec.pid.toString(16)}") }
                                .getOrNull() ?: PidResponse.Garbled(spec.pid, "exception")
                            val rtt = System.currentTimeMillis() - t0
                            _latency.tryEmit(LatencySample(spec.pid, rtt, t0))
                            if (r is PidResponse.Ok) fold(r)
                            delay(20)
                        }
                        lastPidBurstMs = now
                        continue
                    }

                    // Otherwise stay in monitor. Start it if it's not running.
                    if (!weOwnMonitor && session.phase.value != ObdSession.Phase.Monitoring) {
                        Timber.d("Poll loop: entering CAN monitor mode")
                        session.startMonitor(MazdaNcDbc.SubscribedIds)
                        weOwnMonitor = (session.phase.value == ObdSession.Phase.Monitoring)
                        if (!weOwnMonitor) {
                            // startMonitor refused (session disconnected, etc). Back off.
                            Timber.w("Poll loop: startMonitor failed, phase=${session.phase.value}")
                            delay(500)
                            continue
                        }
                    }

                    // Snooze until it's time to consider the next burst.
                    delay(200)
                }
            } finally {
                if (weOwnMonitor) {
                    runCatching { session.stopMonitor() }
                    weOwnMonitor = false
                }
            }
        }
    }

    /**
     * Continuous CAN-frame collector. Every parsed frame is decoded via [MazdaNcDbc] and
     * folded into the snapshot, throttled per-ID to avoid 100 Hz Compose recompositions.
     * Runs whenever the session is alive — independent of who started monitor mode.
     */
    private fun startCanFold() {
        val s = scope ?: return
        canFoldJob?.cancel()
        canFoldJob = s.launch {
            var frameCount = 0
            var lastLogMs = System.currentTimeMillis()
            session.canLines.collect { line ->
                val frame = CanFrameParser.parse(line.line, line.tsMs) ?: return@collect
                val decoded = MazdaNcDbc.BY_ID[frame.id]?.decode?.invoke(frame) ?: return@collect
                foldCanFrame(frame.id, decoded)
                // Periodic heartbeat: confirms CAN is actually flowing. Logs roughly once per
                // second when frames are arriving. If you don't see this in logcat, the bus
                // isn't streaming and the dashboard's "live" values are stale by design.
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastLogMs >= 1_000) {
                    Timber.d("CAN fold: ${frameCount} frames in last ${now - lastLogMs}ms")
                    frameCount = 0
                    lastLogMs = now
                }
            }
        }
    }

    /**
     * Apply one decoded CAN frame to the snapshot. Throttled per-ID so the UI sees at most
     * ~10 Hz updates per signal regardless of how fast the bus broadcasts.
     */
    private fun foldCanFrame(id: Int, decoded: Map<String, Double>) {
        if (decoded.isEmpty()) return
        val now = System.currentTimeMillis()
        // Record the raw CAN frame rate (pre-throttle) so the stats panel reflects what's
        // actually flowing on the bus, not the UI-throttled view of it.
        rateTracker.record("can_${"%03X".format(id)}", now)

        val last = canFoldLastTsMs[id] ?: 0L
        if (now - last < CAN_FOLD_MIN_INTERVAL_MS) return
        canFoldLastTsMs[id] = now

        when (id) {
            MazdaNcDbc.WHEEL_SPEEDS.id -> {
                val ws = WheelSpeeds(
                    fl = decoded["fl_kph"] ?: 0.0,
                    fr = decoded["fr_kph"] ?: 0.0,
                    rl = decoded["rl_kph"] ?: 0.0,
                    rr = decoded["rr_kph"] ?: 0.0,
                )
                _snapshots.update { it.copy(tsMs = now, wheelSpeeds = Reading(ws, now)) }
            }
            MazdaNcDbc.PCM_201.id -> {
                _snapshots.update { snap ->
                    snap.copy(
                        tsMs = now,
                        rpm = decoded["rpm"]?.let { Reading(it, now) } ?: snap.rpm,
                        speedKph = decoded["speed_kph"]?.let { Reading(it, now) } ?: snap.speedKph,
                        acceleratorPct = decoded["accelerator_pct"]?.let { Reading(it, now) } ?: snap.acceleratorPct,
                    )
                }
            }
            MazdaNcDbc.ENGINE_240.id -> {
                _snapshots.update { snap ->
                    snap.copy(
                        tsMs = now,
                        engineLoadPct = decoded["engine_load_pct"]?.let { Reading(it, now) } ?: snap.engineLoadPct,
                        coolantC = decoded["coolant_c"]?.let { Reading(it, now) } ?: snap.coolantC,
                        iatC = decoded["iat_c"]?.let { Reading(it, now) } ?: snap.iatC,
                        throttlePct = decoded["throttle_valve_pct"]?.let { Reading(it, now) } ?: snap.throttlePct,
                    )
                }
            }
            MazdaNcDbc.TRANS_231.id -> {
                val pressed = (decoded["clutch_switch"] ?: 0.0) >= 0.5
                _snapshots.update { it.copy(tsMs = now, clutchSwitch = Reading(pressed, now)) }
            }
        }
    }

    /** Whether the main loop currently holds CAN monitor mode (used to distinguish from external monitors). */
    @Volatile private var weOwnMonitor: Boolean = false

    /** Periodically publishes a snapshot of the rate tracker to the UI flow. */
    private fun startRatesEmitter() {
        val s = scope ?: return
        ratesJob?.cancel()
        ratesJob = s.launch {
            while (true) {
                _rates.value = rateTracker.snapshot()
                delay(1_000)
            }
        }
    }

    private companion object {
        /** How often the main loop drops out of CAN monitor to poll PID-only fields. */
        const val PID_BURST_INTERVAL_MS = 3_000L

        /** Per-ID minimum interval between snapshot updates from the CAN fold (~10 Hz). */
        const val CAN_FOLD_MIN_INTERVAL_MS = 100L
    }

    private fun fold(ok: PidResponse.Ok) {
        val ts = System.currentTimeMillis()
        rateTracker.record("pid_${"%02X".format(ok.pid)}", ts)
        _snapshots.update { snap ->
            when (ok.pid) {
                Pid.RPM.pid           -> snap.copy(tsMs = ts, rpm = Reading(ok.value, ts))
                Pid.SPEED.pid         -> snap.copy(tsMs = ts, speedKph = Reading(ok.value, ts))
                Pid.COOLANT.pid       -> snap.copy(tsMs = ts, coolantC = Reading(ok.value, ts))
                Pid.IAT.pid           -> snap.copy(tsMs = ts, iatC = Reading(ok.value, ts))
                Pid.THROTTLE.pid      -> snap.copy(tsMs = ts, throttlePct = Reading(ok.value, ts))
                Pid.MAF.pid           -> snap.copy(tsMs = ts, mafGps = Reading(ok.value, ts))
                Pid.ENGINE_LOAD.pid   -> snap.copy(tsMs = ts, engineLoadPct = Reading(ok.value, ts))
                Pid.TIMING_ADV.pid    -> snap.copy(tsMs = ts, timingDeg = Reading(ok.value, ts))
                Pid.BATTERY.pid       -> snap.copy(tsMs = ts, batteryV = Reading(ok.value, ts))
                Pid.STFT_B1.pid       -> snap.copy(tsMs = ts, stftPct = Reading(ok.value, ts))
                Pid.LTFT_B1.pid       -> snap.copy(tsMs = ts, ltftPct = Reading(ok.value, ts))
                Pid.O2_B1S1_VOLT.pid  -> snap.copy(tsMs = ts, o2PreV = Reading(ok.value, ts))
                Pid.O2_B1S2_VOLT.pid  -> snap.copy(tsMs = ts, o2PostV = Reading(ok.value, ts))
                else -> snap
            }
        }
    }
}
