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
 * Fake transport that synthesizes realistic OBDLink MX+ output. Default in debug builds.
 *
 * Behavior:
 *  - Responds to AT* commands with adapter-shaped acknowledgements.
 *  - Responds to Mode 01 PID queries with formatted responses whose decoded values come
 *    from a simple driving simulation (RPM oscillates, coolant rises, throttle wiggles).
 *  - Responds to Mode 03 / 06 / 07 / 09 with plausible stub data.
 *  - Mimics ELM timing including the trailing prompt `>`.
 *
 * Lets you exercise the entire app on the emulator without an adapter or a car.
 */
@Singleton
class MockTransport @Inject constructor() : Transport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val displayName: String = "Mock adapter (synthesized)"

    private val pendingResponses = ArrayDeque<String>()
    private val sim = DrivingSim()

    override suspend fun open() {
        _state.value = TransportState.Opening
        delay(150)
        _state.value = TransportState.Open
    }

    override suspend fun close() {
        _state.value = TransportState.Closed
    }

    override fun incoming(): Flow<ByteArray> = channelFlow {
        // Initial banner some adapters send on power-up
        send("ELM327 v1.5 (mock)\r\r>".toByteArray())
        while (!isClosedForSend && _state.value is TransportState.Open) {
            // Pump the response queue
            while (pendingResponses.isNotEmpty()) {
                val r = pendingResponses.removeFirst()
                // Realistic 20-50ms turnaround
                delay(Random.nextLong(20, 50))
                send((r + "\r>").toByteArray())
            }
            delay(10)
        }
        awaitClose { }
    }

    override suspend fun write(bytes: ByteArray) {
        val cmd = bytes.toString(Charsets.US_ASCII).trim().uppercase()
        sim.tick()
        pendingResponses.addLast(synthesize(cmd))
    }

    private fun synthesize(cmd: String): String = when {
        cmd.startsWith("AT") -> handleAt(cmd)
        cmd.startsWith("ST") -> handleSt(cmd)
        cmd.startsWith("01") -> handleMode01(cmd.removePrefix("01"))
        cmd.startsWith("03") -> "43 01 33 00 00 00 00"   // single DTC P0133 (lazy O2 sensor) — illustrative
        cmd.startsWith("07") -> "47 00 00 00 00 00 00"   // no pending DTCs
        cmd.startsWith("06") -> handleMode06(cmd.removePrefix("06"))
        cmd.startsWith("09") -> handleMode09(cmd.removePrefix("09"))
        else -> "?"
    }

    private fun handleAt(cmd: String): String = when (cmd) {
        "ATZ" -> "ELM327 v1.5 (mock)"
        "ATE0", "ATE1", "ATL0", "ATL1", "ATS0", "ATS1", "ATH0", "ATH1", "ATAT1", "ATAT0", "ATAT2" -> "OK"
        "ATSP6", "ATSP0", "ATSPA6" -> "OK"
        "ATRV" -> "%.1fV".format(sim.batteryV)
        "ATDP" -> "ISO 15765-4 (CAN 11/500)"
        else -> "OK"
    }

    private fun handleSt(cmd: String): String = when {
        cmd.startsWith("STFAP") -> "OK"
        cmd.startsWith("STFAB") -> "OK"
        cmd.startsWith("STFCP") -> "OK"
        cmd == "STM" -> {
            // For the mock we don't continuously stream; CanMonitorScreen pages frames via PidExplorer's
            // stub flow instead. A real STN would emit raw frames here until any byte interrupted.
            "STOPPED"
        }
        else -> "OK"
    }

    private fun handleMode01(pid: String): String {
        val pidByte = pid.trim().take(2).uppercase()
        val payload = when (pidByte) {
            "00" -> "BE 3F A8 13"                           // supported PIDs 01-20 bitmap (mock-y)
            "01" -> "07 65 04 00"                           // monitor status (illustrative)
            "04" -> "%02X".format(sim.engineLoadByte())     // engine load
            "05" -> "%02X".format(sim.coolantByte())        // coolant
            "06" -> "%02X".format((128 + 4).coerceIn(0, 255))   // STFT B1 ~ +3%
            "07" -> "%02X".format((128 + 2).coerceIn(0, 255))   // LTFT B1 ~ +1.5%
            "0C" -> "%02X %02X".format(sim.rpmHi(), sim.rpmLo())
            "0D" -> "%02X".format(sim.speedKph.toInt().coerceIn(0, 255))
            "0E" -> "%02X".format(((sim.timingDeg + 64) * 2).toInt().coerceIn(0, 255))
            "0F" -> "%02X".format((sim.iatC + 40).toInt().coerceIn(0, 255))
            "10" -> {
                val maf = (sim.mafGps * 100).toInt().coerceAtLeast(0)
                "%02X %02X".format((maf shr 8) and 0xFF, maf and 0xFF)
            }
            "11" -> "%02X".format((sim.throttlePct * 255 / 100).toInt().coerceIn(0, 255))
            "14" -> "%02X %02X".format(sim.o2VoltageByte(pre = true), 0x80 + 4)   // pre-cat O2 + STFT
            "1C" -> "%02X".format(sim.o2VoltageByte(pre = false))                  // post-cat O2 voltage
            "31" -> { val km = sim.kmSinceClear; "%02X %02X".format((km shr 8) and 0xFF, km and 0xFF) }
            "41" -> "00 07 65 04"                           // monitor status this drive cycle
            "42" -> { val mv = (sim.batteryV * 1000).toInt(); "%02X %02X".format((mv shr 8) and 0xFF, mv and 0xFF) }
            else -> return "NO DATA"
        }
        return "41 $pidByte $payload"
    }

    private fun handleMode06(arg: String): String {
        // Mode 06: on-board monitoring test results. Real responses are MID/TID specific.
        // Provide one plausible catalyst-monitor row for the smog screen exerciser.
        return "46 01 01 0B 00 64 00 32 00 96"  // MID 01 TID 01 — fake catalyst test result
    }

    private fun handleMode09(arg: String): String {
        // PID 02 = VIN. Response is multi-frame in reality; the parser handles flow control.
        if (arg.trim().startsWith("02")) {
            // Mocked single-line VIN response (real cars need ISO-TP reassembly).
            return "49 02 01 00 00 00 4A 4D 31 4E 43 32 30 4D 36 36 30 30 30 30 30 31"
        }
        return "NO DATA"
    }

    /**
     * Toy driving simulation. Smooth-ish, plausible. Not a vehicle dynamics model — just produces
     * values that look right on the dashboard so we can verify rendering, units, and refresh rates.
     */
    private class DrivingSim {
        private var t = 0.0
        var rpm = 850
        var speedKph = 0.0
        var coolantC = 25.0
        var iatC = 22.0
        var throttlePct = 0.0
        var mafGps = 1.5
        var timingDeg = 8.0
        var batteryV = 13.8
        var kmSinceClear = 412

        fun tick() {
            t += 0.1
            // RPM oscillates with throttle; gentle sine for visual interest
            rpm = (1500 + 1200 * sin(t * 0.4) + 200 * sin(t * 1.7)).toInt().coerceIn(700, 7000)
            speedKph = (40 + 15 * sin(t * 0.2)).coerceAtLeast(0.0)
            coolantC = (coolantC + 0.05).coerceAtMost(91.0)
            iatC = 22.0 + 3 * sin(t * 0.05)
            throttlePct = (10 + 8 * sin(t * 0.6)).coerceIn(0.0, 100.0)
            mafGps = 4 + 3 * sin(t * 0.5)
            timingDeg = 8 + 6 * sin(t * 0.3)
            batteryV = 14.1 - 0.3 * sin(t * 0.1)
        }

        fun rpmHi(): Int = ((rpm * 4) shr 8) and 0xFF
        fun rpmLo(): Int = (rpm * 4) and 0xFF
        fun coolantByte(): Int = (coolantC + 40).toInt().coerceIn(0, 255)
        fun engineLoadByte(): Int = ((20 + 30 * sin(PI * t / 30)).toInt() * 255 / 100).coerceIn(0, 255)
        fun o2VoltageByte(pre: Boolean): Int {
            // Pre-cat swings 0.1V .. 0.9V (lambda hunting); post-cat sits at ~0.7V (functioning catalyst)
            return if (pre) {
                (((0.5 + 0.4 * sin(t * 3)) * 200).toInt()).coerceIn(0, 255)
            } else {
                (0.7 * 200).toInt()
            }
        }
    }
}
