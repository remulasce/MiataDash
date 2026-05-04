package dev.kirker.miatadash.core.braking

import dev.kirker.miatadash.core.can.CanFrameParser
import dev.kirker.miatadash.core.can.MazdaNcDbc
import dev.kirker.miatadash.core.obd.ObdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detects hard-braking events from raw 100 Hz CAN bus traffic and produces [BrakeEvent] reports.
 *
 * ## Detection: deceleration-based (not wheel-slip-based)
 *
 * Events are triggered by **vehicle deceleration** measured from successive ECU speed readings
 * (0x201), NOT by wheel slip. This means the detector fires on any firm stop — good pedal
 * technique, threshold braking, ABS or no ABS — not just events that happen to lock a wheel.
 *
 * Algorithm:
 *  - Maintain a 250 ms sliding window of `(timestamp, vehicleKph)` pairs from 0x201.
 *  - On each new speed reading compute instantaneous deceleration:
 *    `decel = (speedAtWindowStart − speedNow) / windowSeconds`  (kph/s, positive = slowing)
 *  - **Entry** (IDLE → ACTIVE): `decel ≥ DECEL_START_KPH_S` (≈0.4 g) AND speed above
 *    crawl threshold.
 *  - **Exit** (ACTIVE → IDLE): `decel < DECEL_EXIT_KPH_S` (≈0.1 g) sustained for
 *    [EVENT_CLEAR_DWELL_MS] (500 ms), OR vehicle speed drops below [STOP_SPEED_KPH].
 *  - Events with total speed drop < [MIN_SPEED_DROP_KPH] are discarded (noise rejection).
 *
 * ## Bonus: per-corner slip & ABS pulse analysis
 *
 * While an event is active the detector also processes 0x4B0 wheel speeds and computes
 * per-corner slip (wheel − ECU speed), peak slip, and ABS pulse count. These appear in the
 * [BrakeEvent] report even for events where no wheel actually locked — they'll just show
 * near-zero slip, confirming clean braking technique.
 *
 * ## No DSC brake-pressure sensor
 *
 * Fae's 2006 NC1 lacks the optional DSC module so 0x085 never broadcasts. Deceleration from
 * 0x201 is the correct substitute — it's the authoritative stopping-performance metric anyway.
 */
@Singleton
class BrakeEventDetector @Inject constructor(
    private val session: ObdSession,
) {
    // ── Output ────────────────────────────────────────────────────────────────────────────

    private val _events = MutableStateFlow<List<BrakeEvent>>(emptyList())
    /** Most-recent-first list of completed braking events (capped at [MAX_STORED_EVENTS]). */
    val events: StateFlow<List<BrakeEvent>> = _events.asStateFlow()

    // ── Internal state (single collector coroutine — no locking needed) ──────────────────

    /** Rolling pre-event context: last [PRE_BUFFER_MS] of raw wheel-speed samples at ~100 Hz. */
    private val preBuffer = ArrayDeque<BrakeSample>()

    /** Samples accumulated during the active event (pre-buffer seed + live). */
    private val eventBuffer = ArrayDeque<BrakeSample>()

    /** Sliding window of (tsMs, vehicleKph) from 0x201 for deceleration computation. */
    private val speedWindow = ArrayDeque<Pair<Long, Double>>()

    private var latestVehicleKph = 0.0
    private var state = State.IDLE
    private var clearingSince = 0L
    private var eventStartSpeedKph = 0.0

    /** Per-corner accumulators — reset at event start. */
    private val flAcc = WheelAcc(); private val frAcc = WheelAcc()
    private val rlAcc = WheelAcc(); private val rrAcc = WheelAcc()

    /** SRS accelerometer accumulator — logs raw int16 values during active events. */
    private val srsAcc = SrsAcc()

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        scope.launch {
            session.canLines.collect { canLine ->
                val frame = CanFrameParser.parse(canLine.line, canLine.tsMs) ?: return@collect
                when (frame.id) {
                    MazdaNcDbc.PCM_201.id -> {
                        val d = MazdaNcDbc.PCM_201.decode.invoke(frame)
                        val speed = d["speed_kph"] ?: return@collect
                        latestVehicleKph = speed
                        processSpeedSample(canLine.tsMs, speed)
                    }
                    MazdaNcDbc.WHEEL_SPEEDS.id -> {
                        val d = MazdaNcDbc.WHEEL_SPEEDS.decode.invoke(frame)
                        val fl = d["fl_kph"] ?: return@collect
                        val fr = d["fr_kph"] ?: return@collect
                        val rl = d["rl_kph"] ?: return@collect
                        val rr = d["rr_kph"] ?: return@collect
                        val sample = BrakeSample(canLine.tsMs, fl, fr, rl, rr, latestVehicleKph)
                        processWheelSample(sample)
                    }
                    MazdaNcDbc.SRS_ACCEL_430.id -> {
                        if (state == State.ACTIVE) {
                            val d = MazdaNcDbc.SRS_ACCEL_430.decode.invoke(frame)
                            val r0 = d["accel_raw_0"] ?: return@collect
                            val r2 = d["accel_raw_2"] ?: return@collect
                            srsAcc.update(r0, r2)
                        }
                    }
                }
            }
        }
    }

    fun reset() {
        preBuffer.clear()
        eventBuffer.clear()
        speedWindow.clear()
        state = State.IDLE
        clearingSince = 0L
        latestVehicleKph = 0.0
        listOf(flAcc, frAcc, rlAcc, rrAcc).forEach { it.reset() }
        srsAcc.reset()
        Timber.d("BrakeDetector: reset")
    }

    // ── Deceleration detection (driven by 0x201 at ~100 Hz) ──────────────────────────────

    private fun processSpeedSample(tsMs: Long, speedKph: Double) {
        // Maintain sliding window — extended to FALLBACK_WINDOW_MS (1 s) so both the
        // primary decel check (250 ms reference) and the fallback drop check (1 s reference)
        // share the same ring.
        speedWindow.addLast(Pair(tsMs, speedKph))
        val windowCutoff = tsMs - FALLBACK_WINDOW_MS
        while (speedWindow.firstOrNull()?.first?.let { it < windowCutoff } == true) {
            speedWindow.removeFirst()
        }

        if (speedWindow.size < 2) return

        // ── Primary: instantaneous decel over ~250 ms ────────────────────────────
        // Find the newest sample that is at least DECEL_WINDOW_MS old.  If the window
        // doesn't span 250 ms yet, fall back to the oldest sample we have.
        val ref250 = speedWindow.lastOrNull { tsMs - it.first >= DECEL_WINDOW_MS }
            ?: speedWindow.first()
        val elapsed250S = (tsMs - ref250.first) / 1000.0
        val decelKphS = if (elapsed250S >= 0.05)
            (ref250.second - speedKph) / elapsed250S   // positive = decelerating
        else 0.0

        // ── Fallback: absolute speed drop over the full 1-second window ──────────
        // Independent of the decel formula — catches events where a CAN decode bug
        // or a slow-moving window would suppress the primary trigger.
        val ref1s = speedWindow.first()
        val elapsed1sS = (tsMs - ref1s.first) / 1000.0
        val speedDrop1s = ref1s.second - speedKph   // positive = dropped

        when (state) {
            State.IDLE -> {
                val speedOk    = speedKph > MIN_SPEED_FOR_EVENT_KPH
                val primaryFires  = speedOk && decelKphS >= DECEL_START_KPH_S
                val fallbackFires = speedOk && elapsed1sS >= 0.90 && speedDrop1s >= FALLBACK_DROP_KPH

                if (primaryFires || fallbackFires) {
                    state = State.ACTIVE
                    // Use the 1-second anchor as start speed: it best reflects where braking
                    // began regardless of which trigger fired.
                    eventStartSpeedKph = ref1s.second
                    eventBuffer.clear()
                    eventBuffer.addAll(preBuffer)
                    listOf(flAcc, frAcc, rlAcc, rrAcc).forEach { it.reset() }
                    srsAcc.reset()
                    clearingSince = 0L
                    if (primaryFires) {
                        Timber.d("BrakeDetector: START (primary) @ %.1f kph, decel=%.1f kph/s (%.2f g)",
                            eventStartSpeedKph, decelKphS, decelKphS / G_IN_KPH_S)
                    } else {
                        Timber.d("BrakeDetector: START (fallback) @ %.1f kph, %.1f kph drop in %.2f s",
                            eventStartSpeedKph, speedDrop1s, elapsed1sS)
                    }
                }
            }
            State.ACTIVE -> {
                val eventEnding = decelKphS < DECEL_EXIT_KPH_S || speedKph < STOP_SPEED_KPH
                if (eventEnding) {
                    if (clearingSince == 0L) clearingSince = tsMs
                    val dwellOk = (tsMs - clearingSince) >= EVENT_CLEAR_DWELL_MS
                    val stopped = speedKph < STOP_SPEED_KPH
                    if (dwellOk || stopped) {
                        finalizeEvent(tsMs, speedKph)
                        state = State.IDLE
                    }
                } else {
                    clearingSince = 0L
                }
            }
        }
    }

    // ── Wheel-speed tracking (driven by 0x4B0 at ~100 Hz) ────────────────────────────────

    private fun processWheelSample(s: BrakeSample) {
        maintainPreBuffer(s)

        if (state == State.ACTIVE) {
            eventBuffer.addLast(s)
            while (eventBuffer.size > 2_000) eventBuffer.removeFirst()   // safety cap

            // Compute per-corner deltas and update accumulators
            flAcc.update(s.fl - s.vehicleKph)
            frAcc.update(s.fr - s.vehicleKph)
            rlAcc.update(s.rl - s.vehicleKph)
            rrAcc.update(s.rr - s.vehicleKph)
        }
    }

    // ── Event finalization ────────────────────────────────────────────────────────────────

    private fun finalizeEvent(endMs: Long, endSpeedKph: Double) {
        val speedDrop = eventStartSpeedKph - endSpeedKph
        if (speedDrop < MIN_SPEED_DROP_KPH) {
            Timber.d("BrakeDetector: discarding event (only %.1f kph drop)", speedDrop)
            return
        }
        if (eventBuffer.isEmpty()) {
            Timber.d("BrakeDetector: discarding event (no wheel samples)")
            return
        }

        val event = BrakeEvent(
            id            = endMs,
            startMs       = eventBuffer.first().tsMs,
            endMs         = endMs,
            startSpeedKph = eventStartSpeedKph,
            endSpeedKph   = endSpeedKph,
            samples       = eventBuffer.toList(),
            fl            = flAcc.stats(),
            fr            = frAcc.stats(),
            rl            = rlAcc.stats(),
            rr            = rrAcc.stats(),
            srsRaw0       = srsAcc.stats0(),
            srsRaw2       = srsAcc.stats2(),
        )
        _events.value = listOf(event) + _events.value.take(MAX_STORED_EVENTS - 1)

        Timber.i(
            "BrakeDetector: event COMPLETE — %.1f→%.1f kph (%.1f kph/s avg), %.1fs, " +
            "FL peak=%.1f (${event.fl.absPulses}×ABS) FR=%.1f RL=%.1f RR=%.1f",
            eventStartSpeedKph, endSpeedKph,
            speedDrop / ((endMs - event.startMs) / 1000.0),
            event.durationMs / 1000.0,
            event.fl.peakSlipKph, event.fr.peakSlipKph,
            event.rl.peakSlipKph, event.rr.peakSlipKph,
        )
    }

    private fun maintainPreBuffer(s: BrakeSample) {
        preBuffer.addLast(s)
        val cutoff = s.tsMs - PRE_BUFFER_MS
        while (preBuffer.firstOrNull()?.tsMs?.let { it < cutoff } == true) preBuffer.removeFirst()
    }

    // ── Per-corner accumulator ────────────────────────────────────────────────────────────

    private class WheelAcc {
        private var locked = false
        private var pulses = 0
        private var slipSum = 0.0
        private var slipCount = 0
        private var peakSlip = 0.0

        fun update(delta: Double) {
            slipSum += delta
            slipCount++
            if (delta < peakSlip) peakSlip = delta
            // ABS pulse: wheel locks then ABS releases it
            if (!locked && delta < ABS_LOCK_KPH) locked = true
            else if (locked && delta > ABS_RELEASE_KPH) { locked = false; pulses++ }
        }

        fun stats() = CornerSlipStats(
            avgSlipKph  = if (slipCount > 0) slipSum / slipCount else 0.0,
            peakSlipKph = peakSlip,
            absPulses   = pulses,
        )

        fun reset() { locked = false; pulses = 0; slipSum = 0.0; slipCount = 0; peakSlip = 0.0 }
    }

    /**
     * Accumulates raw SRS accelerometer int16 values (channels 0 and 2) during an active
     * braking event. Produces [SrsRawStats] for min/max/avg calibration.
     *
     * Both channels use separate min/max tracking since they represent different axes.
     */
    private class SrsAcc {
        private var sum0 = 0.0;  private var sum2 = 0.0
        private var min0 = Double.MAX_VALUE; private var max0 = -Double.MAX_VALUE
        private var min2 = Double.MAX_VALUE; private var max2 = -Double.MAX_VALUE
        private var count = 0

        fun update(raw0: Double, raw2: Double) {
            sum0 += raw0;  sum2 += raw2
            if (raw0 < min0) min0 = raw0;  if (raw0 > max0) max0 = raw0
            if (raw2 < min2) min2 = raw2;  if (raw2 > max2) max2 = raw2
            count++
        }

        fun stats0() = if (count > 0) SrsRawStats(min0, max0, sum0 / count, count) else null
        fun stats2() = if (count > 0) SrsRawStats(min2, max2, sum2 / count, count) else null

        fun reset() {
            sum0 = 0.0;  sum2 = 0.0
            min0 = Double.MAX_VALUE;  max0 = -Double.MAX_VALUE
            min2 = Double.MAX_VALUE;  max2 = -Double.MAX_VALUE
            count = 0
        }
    }

    private enum class State { IDLE, ACTIVE }

    // ── Thresholds ────────────────────────────────────────────────────────────────────────

    private companion object {
        /** 1g in kph/s. */
        const val G_IN_KPH_S = 35.304

        /** Entry: 0.4 g deceleration triggers event capture. */
        const val DECEL_START_KPH_S  = G_IN_KPH_S * 0.40   // ≈ 14.1 kph/s

        /** Exit: decel below 0.1 g — car is coasting or stopped. */
        const val DECEL_EXIT_KPH_S   = G_IN_KPH_S * 0.10   // ≈ 3.5 kph/s

        /** How long decel must stay below exit threshold before the event closes. */
        const val EVENT_CLEAR_DWELL_MS = 500L

        /** Sliding window for instantaneous deceleration computation (primary trigger). */
        const val DECEL_WINDOW_MS    = 250L

        /**
         * Fallback trigger: if the car loses at least [FALLBACK_DROP_KPH] within this window
         * the event fires even if the primary 250 ms decel check didn't. Catches events where
         * CAN decode errors or a sluggish window would suppress the primary.
         *
         * 20 mph = 32.2 kph.  Over 1 s that's ≈ 0.91 g — well above normal deceleration.
         * The window also doubles as the speed-ring buffer so both triggers share one deque.
         */
        const val FALLBACK_WINDOW_MS = 1_000L
        const val FALLBACK_DROP_KPH  = 20.0 * 1.60934   // 20 mph → 32.19 kph

        /** Don't trigger braking events near standstill — avoids false positives. */
        const val MIN_SPEED_FOR_EVENT_KPH = 15.0

        /** Consider car stopped below this speed — end event immediately. */
        const val STOP_SPEED_KPH     = 5.0

        /** Minimum total speed loss to be a reportable event (filters road bumps, etc.). */
        const val MIN_SPEED_DROP_KPH = 8.0

        /** Pre-event context window copied into event samples for graph context. */
        const val PRE_BUFFER_MS      = 2_000L

        /** ABS lock threshold for per-corner pulse counting. */
        const val ABS_LOCK_KPH       = -3.0

        /** ABS release threshold (wheel recovers after lock). */
        const val ABS_RELEASE_KPH    = -1.0

        const val MAX_STORED_EVENTS  = 5
    }
}
