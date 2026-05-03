package dev.kirker.miatadash.core.obd

/**
 * Registry of every Mode 01 PID we know how to ask for and decode.
 *
 * This is the single source of truth for "what does the dashboard know about". To add a new
 * PID, add an entry here and reference it from the [Pid] object.
 *
 * @param pid hex byte ID (e.g. 0x0C for engine RPM)
 * @param name short human name
 * @param unit unit string for UI ("rpm", "°C", "%", "g/s", "V")
 * @param numBytes data bytes returned by the ECU (excluding the response header `41 XX`)
 * @param decode function from data bytes to a Double in the unit
 * @param refreshTier polling tier — see PollScheduler
 */
data class PidSpec(
    val pid: Int,
    val name: String,
    val unit: String,
    val numBytes: Int,
    val decode: (data: ByteArray) -> Double,
    val refreshTier: RefreshTier,
)

enum class RefreshTier { Fast, Medium, Slow }

private fun u(b: Byte): Int = b.toInt() and 0xFF
private fun a(d: ByteArray): Int = u(d[0])
private fun a16(d: ByteArray): Int = (u(d[0]) shl 8) or u(d[1])

object Pid {

    // ---- Standard powertrain ----
    val ENGINE_LOAD       = PidSpec(0x04, "Engine load", "%",   1, { a(it) * 100.0 / 255.0 }, RefreshTier.Medium)
    val COOLANT           = PidSpec(0x05, "Coolant",     "°C",  1, { a(it).toDouble() - 40 }, RefreshTier.Slow)
    val STFT_B1           = PidSpec(0x06, "STFT B1",     "%",   1, { (a(it) - 128) * 100.0 / 128.0 }, RefreshTier.Slow)
    val LTFT_B1           = PidSpec(0x07, "LTFT B1",     "%",   1, { (a(it) - 128) * 100.0 / 128.0 }, RefreshTier.Slow)
    val RPM               = PidSpec(0x0C, "RPM",         "rpm", 2, { a16(it) / 4.0 }, RefreshTier.Fast)
    val SPEED             = PidSpec(0x0D, "Speed",       "kph", 1, { a(it).toDouble() }, RefreshTier.Fast)
    val TIMING_ADV        = PidSpec(0x0E, "Timing adv",  "°",   1, { a(it) / 2.0 - 64.0 }, RefreshTier.Medium)
    val IAT               = PidSpec(0x0F, "IAT",         "°C",  1, { a(it).toDouble() - 40 }, RefreshTier.Slow)
    val MAF               = PidSpec(0x10, "MAF",         "g/s", 2, { a16(it) / 100.0 }, RefreshTier.Medium)
    val THROTTLE          = PidSpec(0x11, "Throttle",    "%",   1, { a(it) * 100.0 / 255.0 }, RefreshTier.Fast)
    val BATTERY           = PidSpec(0x42, "Battery",     "V",   2, { a16(it) / 1000.0 }, RefreshTier.Slow)

    // ---- Smog / emissions ----
    val MONITOR_STATUS    = PidSpec(0x01, "Monitors (since clear)", "", 4, { a16(it).toDouble() }, RefreshTier.Slow)
    val FUEL_SYS_STATUS   = PidSpec(0x03, "Fuel system status",     "", 2, { a(it).toDouble() }, RefreshTier.Slow)
    val O2_B1S1_VOLT      = PidSpec(0x14, "O2 B1S1 (pre-cat)",  "V", 2, { u(it[0]) / 200.0 }, RefreshTier.Medium)
    val O2_B1S2_VOLT      = PidSpec(0x15, "O2 B1S2 (post-cat)", "V", 2, { u(it[0]) / 200.0 }, RefreshTier.Medium)
    val DIST_SINCE_CLEAR  = PidSpec(0x31, "Distance since clear", "km", 2, { a16(it).toDouble() }, RefreshTier.Slow)
    val MONITOR_THIS_CYC  = PidSpec(0x41, "Monitors (this cycle)", "", 4, { a16(it).toDouble() }, RefreshTier.Slow)

    /** Default dashboard set — what we poll by default. */
    val Dashboard: List<PidSpec> = listOf(
        RPM, SPEED, COOLANT, IAT, THROTTLE, MAF, ENGINE_LOAD, TIMING_ADV, BATTERY, STFT_B1, LTFT_B1
    )

    /**
     * PIDs that have NO Mazda-CAN equivalent we can decode. The dashboard's CAN-default loop
     * polls these during periodic short bursts, since CAN gives us most other dashboard fields
     * (RPM, speed, coolant, IAT, throttle, engine load) at higher rates anyway.
     *
     * - [MAF] — not in our DBC; only available via PID 0x10.
     * - [BATTERY] — PID 0x42 / ATRV.
     * - [STFT_B1], [LTFT_B1] — fuel trims, slow-changing, PID-only.
     * - [TIMING_ADV] — CAN 0x240 has a raw byte but the scale isn't documented; keep PID for now.
     */
    val PidOnlyDashboard: List<PidSpec> = listOf(
        MAF, BATTERY, STFT_B1, LTFT_B1, TIMING_ADV,
    )

    /** Smog screen set. */
    val Smog: List<PidSpec> = listOf(
        MONITOR_STATUS, MONITOR_THIS_CYC, FUEL_SYS_STATUS,
        O2_B1S1_VOLT, O2_B1S2_VOLT, DIST_SINCE_CLEAR
    )

    /** Lookup by hex ID for the PID Explorer. */
    val All: Map<Int, PidSpec> = listOf(
        ENGINE_LOAD, COOLANT, STFT_B1, LTFT_B1, RPM, SPEED, TIMING_ADV, IAT, MAF, THROTTLE, BATTERY,
        MONITOR_STATUS, FUEL_SYS_STATUS, O2_B1S1_VOLT, O2_B1S2_VOLT, DIST_SINCE_CLEAR, MONITOR_THIS_CYC
    ).associateBy { it.pid }
}
