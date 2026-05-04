package dev.kirker.miatadash.core.transport

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fake transport that synthesizes realistic OBDLink MX+ / STN1110 output.
 *
 * Two modes:
 *
 *  **Command/response mode** (default, and while PID polling is active)
 *    Responds to AT* / ST* setup commands and Mode 01 PID queries with correctly-formatted
 *    ELM327 lines terminated by `\r>`. Used by [ObdSession.sendAndAwait] and [ObdSession.query].
 *
 *  **CAN monitor mode** (entered after `STM` is received)
 *    Instead of queued responses, the [incoming] flow continuously emits Mazda NC CAN frame
 *    lines terminated by `\r` (no `>` — identical to real STN1110 behaviour). These are
 *    decoded by [dev.kirker.miatadash.core.can.CanFrameParser] and folded into the telemetry
 *    snapshot by [dev.kirker.miatadash.core.telemetry.TelemetryRepository].
 *
 *    Frames emitted:
 *      0x201  PCM (RPM, speed, accelerator pedal)            ~50 Hz
 *      0x240  Engine block (load, coolant, throttle, IAT)    ~10 Hz
 *      0x4B0  Wheel speeds (FL/FR/RL/RR with jitter)        ~50 Hz
 *      0x231  Transmission / clutch switch                   ~25 Hz
 *
 *    Any byte received while in monitor mode exits it (matches real STN1110 behaviour —
 *    `stopMonitor` sends a bare `\r` which triggers this).
 *
 * Frame encoding: spaces-off (`ATS0` is in the init sequence), so each line is the 3-hex-char
 * CAN ID immediately followed by 2-hex-char bytes, no separators, terminated by `\r`.
 * [dev.kirker.miatadash.core.can.CanFrameParser] strips all non-hex chars before parsing, so
 * the format is forward-compatible with spaces-on output too.
 */
@Singleton
class MockTransport @Inject constructor() : Transport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val displayName: String = "Mock adapter (synthesized)"

    /** Queued responses for command/response mode. */
    private val pendingResponses = ArrayDeque<String>()

    /** Shared driving simulation state. */
    private val sim = DrivingSim()

    /**
     * Set to true when STM is received; cleared when any byte arrives while streaming.
     * Volatile because it is written by [write] (called from Dispatchers.IO) and read by
     * the [incoming] collector (a different coroutine).
     */
    @Volatile private var inMonitorMode = false

    override suspend fun open() {
        _state.value = TransportState.Opening
        delay(150)
        _state.value = TransportState.Open
    }

    override suspend fun close() {
        inMonitorMode = false
        pendingResponses.clear()
        _state.value = TransportState.Closed
    }

    override fun incoming(): Flow<ByteArray> = channelFlow {
        // Initial banner some adapters emit on power-up.
        send("ELM327 v1.5 (mock)\r\r>".toByteArray())

        var loopTick = 0
        while (!isClosedForSend && _state.value is TransportState.Open) {
            sim.tick()
            loopTick++

            if (inMonitorMode) {
                // ── CAN monitor mode ────────────────────────────────────────────────
                // Emit frame lines at rates that approximate the real Mazda NC bus.
                // All lines are \r-terminated (no > prompt — identical to real STN1110).
                val sb = StringBuilder()

                // 0x201 PCM: every tick (~50 Hz)
                sb.append(sim.frame201()).append('\r')

                // 0x4B0 wheel speeds: every tick (~50 Hz)
                sb.append(sim.frame4B0()).append('\r')

                // 0x231 clutch: every 2nd tick (~25 Hz)
                if (loopTick % 2 == 0) sb.append(sim.frame231()).append('\r')

                // 0x240 engine block: every 5th tick (~10 Hz)
                if (loopTick % 5 == 0) sb.append(sim.frame240()).append('\r')

                send(sb.toString().toByteArray(Charsets.US_ASCII))
            } else {
                // ── Command / response mode ─────────────────────────────────────────
                while (pendingResponses.isNotEmpty()) {
                    val r = pendingResponses.removeFirst()
                    delay(Random.nextLong(15, 40))   // realistic adapter turnaround
                    send("$r\r>".toByteArray(Charsets.US_ASCII))
                }
            }

            delay(20)   // loop cadence: ~50 Hz
        }
        awaitClose { }
    }

    override suspend fun write(bytes: ByteArray) {
        val cmd = bytes.toString(Charsets.US_ASCII).trim().uppercase()

        if (inMonitorMode) {
            // Any byte from the host exits monitor mode (real STN1110 behaviour).
            // [ObdSession.stopMonitor] sends a bare \r which lands here as an empty string.
            inMonitorMode = false
            return
        }

        // Normal command/response: synthesize a reply and queue it.
        pendingResponses.addLast(synthesize(cmd))
    }

    // ── Command synthesis ──────────────────────────────────────────────────────────────────

    private fun synthesize(cmd: String): String = when {
        cmd.isEmpty()            -> ""   // bare \r during non-monitor teardown — no reply
        cmd.startsWith("AT")     -> handleAt(cmd)
        cmd.startsWith("ST")     -> handleSt(cmd)
        cmd.startsWith("01")     -> handleMode01(cmd.removePrefix("01").trim())
        cmd.startsWith("03")     -> "43 01 33 00 00 00 00"   // P0133 lazy O2 — illustrative
        cmd.startsWith("07")     -> "47 00 00 00 00 00 00"   // no pending DTCs
        cmd.startsWith("06")     -> "46 01 01 0B 00 64 00 32 00 96"
        cmd.startsWith("09")     -> handleMode09(cmd.removePrefix("09").trim())
        else                     -> "?"
    }

    private fun handleAt(cmd: String): String = when (cmd) {
        "ATZ"  -> "ELM327 v1.5 (mock)"
        "ATRV" -> "%.1fV".format(sim.batteryV)
        "ATDP" -> "ISO 15765-4 (CAN 11/500)"
        else   -> "OK"   // ATE0, ATL0, ATS0, ATH1, ATSP6, ATAT1, ATAT2, …
    }

    private fun handleSt(cmd: String): String = when {
        // Filter setup commands — acknowledged but we emit all subscribed IDs regardless.
        cmd.startsWith("STFAC") || cmd.startsWith("STFAP") ||
        cmd.startsWith("STFAB") || cmd.startsWith("STFCP") -> "OK"

        cmd == "STM" -> {
            // Enter monitor mode. Clear any stale queued responses so they don't leak into
            // the post-monitor command session when we eventually exit.
            pendingResponses.clear()
            inMonitorMode = true
            // No reply — the real STN1110 also goes silent and starts streaming.
            ""
        }

        else -> "OK"
    }

    private fun handleMode01(pid: String): String {
        val pidByte = pid.take(2).uppercase()
        val payload = when (pidByte) {
            "00" -> "BE 3F A8 13"
            "01" -> "07 65 04 00"
            "04" -> "%02X".format(sim.engineLoadByte())
            "05" -> "%02X".format(sim.coolantByte())
            "06" -> "%02X".format((128 + 4).coerceIn(0, 255))    // STFT B1 ~ +3 %
            "07" -> "%02X".format((128 + 2).coerceIn(0, 255))    // LTFT B1 ~ +1.5 %
            "0C" -> "%02X %02X".format(sim.rpmHi(), sim.rpmLo())
            "0D" -> "%02X".format(sim.speedKph.toInt().coerceIn(0, 255))
            "0E" -> "%02X".format(((sim.timingDeg + 64) * 2).toInt().coerceIn(0, 255))
            "0F" -> "%02X".format((sim.iatC + 40).toInt().coerceIn(0, 255))
            "10" -> {
                val maf = (sim.mafGps * 100).toInt().coerceAtLeast(0)
                "%02X %02X".format((maf shr 8) and 0xFF, maf and 0xFF)
            }
            "11" -> "%02X".format((sim.throttlePct * 255 / 100).toInt().coerceIn(0, 255))
            "14" -> "%02X %02X".format(sim.o2VoltageByte(pre = true), 0x80 + 4)
            "1C" -> "%02X".format(sim.o2VoltageByte(pre = false))
            "31" -> {
                val km = sim.kmSinceClear
                "%02X %02X".format((km shr 8) and 0xFF, km and 0xFF)
            }
            "41" -> "00 07 65 04"
            "42" -> {
                val mv = (sim.batteryV * 1000).toInt()
                "%02X %02X".format((mv shr 8) and 0xFF, mv and 0xFF)
            }
            else -> return "NO DATA"
        }
        return "41 $pidByte $payload"
    }

    private fun handleMode09(arg: String): String {
        if (arg.startsWith("02")) {
            return "49 02 01 00 00 00 4A 4D 31 4E 43 32 30 4D 36 36 30 30 30 30 30 31"
        }
        return "NO DATA"
    }

    // ── Driving simulation ─────────────────────────────────────────────────────────────────

    /**
     * Toy driving simulation. Updated at ~50 Hz by the [incoming] loop regardless of mode,
     * so the animation is consistent whether the dashboard uses CAN or PID.
     *
     * All CAN frame encoders are methods on this class so they can read state directly.
     *
     * Encoding reference — each method is the inverse of the matching [MazdaNcDbc] decoder:
     *   RPM          : uint16 = rpm × 4       (PCM_201 bytes 0-1)
     *   speed        : int16  = (kph + 100) × 100  (PCM_201 bytes 4-5, WHEEL_SPEEDS bytes 0-7)
     *   load/tps/iat : uint8  = value × 255 / range (ENGINE_240 bytes 0/3)
     *   coolant/IAT  : uint8  = °C + 40       (ENGINE_240 bytes 1/4)
     */
    private class DrivingSim {
        private var t = 0.0
        var rpm        = 850
        var speedKph   = 0.0
        var coolantC   = 25.0
        var iatC       = 22.0
        var throttlePct = 0.0
        var mafGps     = 1.5
        var timingDeg  = 8.0
        var batteryV   = 13.8
        var kmSinceClear = 412

        // ── Braking simulation state ──────────────────────────────────────────────
        // A hard-braking event fires every BRAKE_PERIOD_TICKS (~30 s), lasting
        // BRAKE_DURATION_TICKS (~2.5 s). Simulates aggressive threshold braking
        // from highway speed (~90 kph) to a near-stop: ~34 kph/s ≈ 0.96 g.
        // Each wheel shows ABS-style pulsing slip at corner-specific frequencies.
        private var brakeTick = 0
        private var prebrakeSpeed = 90.0   // captured just as braking starts

        private companion object {
            const val BRAKE_PERIOD_TICKS   = 1500   // 30 s at 50 Hz
            const val BRAKE_DURATION_TICKS = 125    // 2.5 s at 50 Hz
        }

        /** True when we are inside the simulated braking window this tick. */
        val inBraking: Boolean get() = brakeTick % BRAKE_PERIOD_TICKS < BRAKE_DURATION_TICKS

        /** Progress through the braking window 0.0..1.0, 0 outside window. */
        private val brakeProgress: Double
            get() {
                val phase = brakeTick % BRAKE_PERIOD_TICKS
                return if (phase < BRAKE_DURATION_TICKS) phase.toDouble() / BRAKE_DURATION_TICKS else 0.0
            }

        fun tick() {
            t += 0.04   // ~50 Hz increments; keeps animation rate constant
            brakeTick++

            val phase = brakeTick % BRAKE_PERIOD_TICKS

            if (phase == 0) {
                // Capture cruising speed as the braking event begins
                prebrakeSpeed = speedKph.coerceAtLeast(50.0)
            }

            if (inBraking) {
                // Linear decel from prebrakeSpeed to ~6 % of it over the braking window
                // (2.5 s). At 90 kph cruise this is ~84 kph drop / 2.5 s ≈ 34 kph/s ≈ 0.96 g —
                // well above the 0.4 g event-entry threshold.
                speedKph    = (prebrakeSpeed * (1.0 - brakeProgress * 0.94)).coerceAtLeast(5.0)
                rpm         = (speedKph * 75.0).toInt().coerceIn(700, 3000)   // engine braking
                throttlePct = 0.0
            } else {
                // Normal highway cruise 70–110 kph with gentle sinusoidal variation
                speedKph    = (90 + 20 * sin(t * 0.15)).coerceAtLeast(0.0)
                rpm         = (3000 + 1200 * sin(t * 0.4) + 200 * sin(t * 1.7)).toInt().coerceIn(700, 7000)
                throttlePct = (25 + 12 * sin(t * 0.6)).coerceIn(0.0, 100.0)
            }

            coolantC   = (coolantC + 0.02).coerceAtMost(91.0)
            iatC       = 22.0 + 3 * sin(t * 0.05)
            mafGps     = 4.0 + 3 * sin(t * 0.5)
            timingDeg  = 8.0 + 6 * sin(t * 0.3)
            batteryV   = 14.1 - 0.3 * sin(t * 0.1)
        }

        // ── PID helpers (unchanged — used by Mode 01 responses) ──────────────────

        fun rpmHi(): Int = ((rpm * 4) shr 8) and 0xFF
        fun rpmLo(): Int  = (rpm * 4) and 0xFF
        fun coolantByte(): Int = (coolantC + 40).toInt().coerceIn(0, 255)
        fun engineLoadByte(): Int =
            ((20 + 30 * sin(PI * t / 30)).toInt() * 255 / 100).coerceIn(0, 255)
        fun o2VoltageByte(pre: Boolean): Int = if (pre)
            ((0.5 + 0.4 * sin(t * 3)) * 200).toInt().coerceIn(0, 255)
        else
            (0.7 * 200).toInt()

        // ── CAN frame encoders ────────────────────────────────────────────────────

        /**
         * 0x201 — PCM (RPM, vehicle speed, accelerator pedal).
         *
         *   Byte 0-1  RPM       uint16 BE = rpm × 4
         *   Byte 2-3  unused    0x0000
         *   Byte 4-5  speed     int16  BE = (kph + 100) × 100
         *   Byte 6    accel     uint8  = pedal% / 2  (0-100% → 0-50 raw, *2 on decode)
         *   Byte 7    unused    0x00
         */
        fun frame201(): String {
            val rpmRaw   = (rpm * 4).coerceIn(0, 65535)
            val spdRaw   = encodeSpeedField(speedKph)
            val accelRaw = (throttlePct / 2.0).toInt().coerceIn(0, 127)
            return "201%02X%02X0000%02X%02X%02X00".format(
                (rpmRaw shr 8) and 0xFF, rpmRaw and 0xFF,
                (spdRaw shr 8) and 0xFF, spdRaw and 0xFF,
                accelRaw,
            )
        }

        /**
         * 0x240 — Engine block (load, coolant, timing, throttle valve, IAT).
         *
         *   Byte 0  load     uint8 = load% × 255 / 100
         *   Byte 1  coolant  uint8 = °C + 40
         *   Byte 2  timing   uint8 (raw, not decoded by dashboard — set to 0x40)
         *   Byte 3  throttle uint8 = tps% × 255 / 100
         *   Byte 4  IAT      uint8 = °C + 40
         *   Byte 5-7 unused  0x000000
         */
        fun frame240(): String {
            val loadRaw  = engineLoadByte()
            val coolRaw  = coolantByte()
            val tpsRaw   = (throttlePct * 255.0 / 100.0).toInt().coerceIn(0, 255)
            val iatRaw   = (iatC + 40).toInt().coerceIn(0, 255)
            return "240%02X%02X40%02X%02X000000".format(loadRaw, coolRaw, tpsRaw, iatRaw)
        }

        /**
         * 0x4B0 — Wheel speeds (FL / FR / RL / RR).
         *
         * Each wheel is int16 BE = (kph + 100) × 100.
         *
         * **Normal driving**: slow cornering differential (±3 kph side-to-side) plus
         * per-corner shimmy, producing clearly separated delta lines.
         *
         * **Braking window**: each corner applies ABS-style pulsed slip at a unique
         * frequency (5–14 tick periods = 2.8–8.3 Hz). A half-sine intensity envelope
         * builds up and fades so the event has a realistic shape. Rear wheels use
         * higher peak slip since they lock more easily on a lightweight sports car.
         *
         *   ABS pulsing formula per corner:
         *     slip = −peakKph × intensity × (0.5 + 0.5 × sin(phase × π / halfPeriod))
         *   When sin = −1 → factor = 0 → wheel recovered (ABS released)
         *   When sin = +1 → factor = 1 → wheel fully locked
         */
        fun frame4B0(): String {
            val fl: Int; val fr: Int; val rl: Int; val rr: Int

            if (inBraking) {
                val phase = brakeTick % BRAKE_PERIOD_TICKS
                // Half-sine intensity 0→1→0 across the braking window
                val intensity = sin(brakeProgress * PI).coerceIn(0.0, 1.0)

                // ABS slip per corner: peak magnitude × intensity × pulsing factor.
                // Different halfPeriods (ticks) → different ABS frequencies per corner.
                fun absSlip(peakKph: Double, halfPeriod: Int): Double =
                    -peakKph * intensity * (0.5 + 0.5 * sin(phase * PI / halfPeriod))

                val flSlip = absSlip(5.5, 7)    // ~7 Hz — front-left ABS
                val frSlip = absSlip(4.5, 9)    // ~5.5 Hz — front-right ABS
                val rlSlip = absSlip(6.5, 5)    // ~10 Hz — rear-left (locks harder)
                val rrSlip = absSlip(4.0, 11)   // ~4.5 Hz — rear-right

                fl = encodeSpeedField((speedKph + flSlip).coerceAtLeast(0.0))
                fr = encodeSpeedField((speedKph + frSlip).coerceAtLeast(0.0))
                rl = encodeSpeedField((speedKph + rlSlip).coerceAtLeast(0.0))
                rr = encodeSpeedField((speedKph + rrSlip).coerceAtLeast(0.0))
            } else {
                // Normal cornering + shimmy
                val cornerDelta = 3.0 * sin(t * 0.10)
                fl = encodeSpeedField((speedKph - cornerDelta + 1.2 * sin(t * 0.93)).coerceAtLeast(0.0))
                fr = encodeSpeedField((speedKph + cornerDelta - 1.0 * sin(t * 0.87)).coerceAtLeast(0.0))
                rl = encodeSpeedField((speedKph - cornerDelta - 1.5 * sin(t * 0.79)).coerceAtLeast(0.0))
                rr = encodeSpeedField((speedKph + cornerDelta + 1.3 * sin(t * 0.83)).coerceAtLeast(0.0))
            }

            return "4B0%02X%02X%02X%02X%02X%02X%02X%02X".format(
                (fl shr 8) and 0xFF, fl and 0xFF,
                (fr shr 8) and 0xFF, fr and 0xFF,
                (rl shr 8) and 0xFF, rl and 0xFF,
                (rr shr 8) and 0xFF, rr and 0xFF,
            )
        }

        /**
         * 0x231 — Transmission / clutch switch.
         *
         *   Byte 0  unused
         *   Byte 1  clutch MSB = 0x80 when pressed, 0x00 when released
         *
         * Simulates a shift: clutch "pressed" briefly when RPM > 4 500 and a sine envelope
         * exceeds a threshold, giving the strip chart an occasional clutch event to show.
         */
        fun frame231(): String {
            val clutchPressed = rpm > 4500 && sin(t * 0.3) > 0.75
            val byte1 = if (clutchPressed) 0x80 else 0x00
            // Byte 0 = 0x00 (unused), Byte 1 = clutch MSB.
            // "231" + 4 hex chars = 7 cleaned chars → (7-3)%2=0 ✓, 2 data bytes.
            // Decoder: msb(u8(f, 1)) reads bit 7 of byte[1].
            return "231%02X%02X".format(0x00, byte1)
        }

        /**
         * Encodes a speed value (kph) into the Mazda NC int16 big-endian wire format.
         * Formula (inverse of DBC decode): raw = (kph + 100) × 100.
         * Clamped to non-negative values — reverse speeds are not simulated.
         */
        private fun encodeSpeedField(kph: Double): Int =
            ((kph + 100.0) * 100.0).toInt().coerceIn(0, 32767)
    }
}
