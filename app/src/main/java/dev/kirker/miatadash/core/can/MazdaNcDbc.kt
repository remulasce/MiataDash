package dev.kirker.miatadash.core.can

/**
 * Mazda NC1 (2006-2008 MX-5) CAN frame map.
 *
 * Source: timurrrr/RaceChronoDiyBleDevice — `can_db/mazda_mx5_nc.md`. Cross-referenced with
 * Miata.net community work (Leisurehound). Entries here are marked `verified = true` because
 * the user has been running these formulas in Torque Pro and confirms they read sane values.
 *
 * Caveats specific to a 2006 NC1:
 *  - `0x081` (steering), `0x085` (brake), `0x090` (acceleration) require the DSC module.
 *    DSC was an option in 2006 — if these IDs don't appear in CAN Monitor on your car,
 *    your 2006 doesn't have DSC and these values won't be available without an alternate
 *    bus. Check the histogram on first capture.
 *  - `0x4B0` (wheel speeds), `0x201` (engine), `0x215`/`0x240` (engine block),
 *    `0x231` (transmission/clutch) come from non-DSC modules and should always be present.
 *
 * Update frequencies (per RaceChrono): most of these broadcast at 100 Hz; `0x240` at 10 Hz;
 * `0x231` at 40 Hz. The bus is busy.
 */
data class FrameSpec(
    val id: Int,
    val name: String,
    val description: String,
    val verified: Boolean,
    val decode: (CanFrame) -> Map<String, Double>,
)

object MazdaNcDbc {

    // ---- Helpers (RaceChrono-style: big-endian byte ordering) ----

    private fun u8(f: CanFrame, i: Int): Int = f.byte(i)
    private fun u16(f: CanFrame, hi: Int): Int = (f.byte(hi) shl 8) or f.byte(hi + 1)
    private fun s16(f: CanFrame, hi: Int): Int {
        val u = u16(f, hi)
        return if (u >= 0x8000) u - 0x10000 else u
    }
    /** Bit 7 of `b` (MSB-first counting, where bit 7 is the leftmost). */
    private fun msb(byte: Int): Int = (byte shr 7) and 0x01

    // Mazda NC speeds: int16 big-endian with a 100 km/h offset, 0.01 km/h per LSB.
    // raw 10000 (0x2710) = standstill; raw 15000 = 50 km/h forward; raw 9000 = 10 km/h reverse.
    private fun decodeSpeedField(rawSigned: Int): Double = (rawSigned / 100.0) - 100.0

    // ---- Frames ----

    /** Wheel speed sensors (ABS module). FL/FR/RL/RR, 100 Hz. */
    val WHEEL_SPEEDS = FrameSpec(
        id = 0x4B0,
        name = "wheel_speeds",
        description = "Per-wheel speed (FL/FR/RL/RR), each int16 big-endian, (raw/100)-100 = kph.",
        verified = true,
    ) { f ->
        if (f.data.size < 8) return@FrameSpec emptyMap()
        mapOf(
            "fl_kph" to decodeSpeedField(s16(f, 0)),
            "fr_kph" to decodeSpeedField(s16(f, 2)),
            "rl_kph" to decodeSpeedField(s16(f, 4)),
            "rr_kph" to decodeSpeedField(s16(f, 6)),
        )
    }

    /** Steering angle sensor (DSC module — 2007+ or DSC-equipped 2006). 100 Hz. */
    val STEERING = FrameSpec(
        id = 0x081,
        name = "steering_angle",
        description = "Steering angle, signed int16 big-endian at bytes 2-3 (DSC required).",
        verified = true,
    ) { f ->
        if (f.data.size < 4) return@FrameSpec emptyMap()
        // RaceChrono exposes the raw signed int directly; unit isn't documented (likely
        // degrees with an unknown scale). We surface raw and let the UI label it.
        mapOf("steering_raw" to s16(f, 2).toDouble())
    }

    /** Brake module (DSC required). 100 Hz. */
    val BRAKE = FrameSpec(
        id = 0x085,
        name = "brake",
        description = "Brake pressure (bytes 0-1 uint16) and switch (byte 2 MSB). DSC required.",
        verified = true,
    ) { f ->
        if (f.data.size < 3) return@FrameSpec emptyMap()
        val raw = u16(f, 0)
        val pressureKpa = (3.4518689053 * raw - 327.27) / 1000.0
        val brakePct = (0.2 * (raw - 102)).coerceIn(0.0, 100.0)
        val switch = msb(u8(f, 2)).toDouble()
        mapOf(
            "brake_pressure_kpa" to pressureKpa,
            "brake_pct" to brakePct,
            "brake_switch" to switch,
        )
    }

    /** PCM broadcast (engine speed, vehicle speed, accelerator). 100 Hz. */
    val PCM_201 = FrameSpec(
        id = 0x201,
        name = "pcm_201",
        description = "Engine RPM (bytes 0-1 u16/4), vehicle speed (bytes 4-5 (s16/100)-100 kph), accelerator pedal (byte 6 ×2 %).",
        verified = true,
    ) { f ->
        if (f.data.size < 7) return@FrameSpec emptyMap()
        val rpm = u16(f, 0) / 4.0
        val speedKph = decodeSpeedField(s16(f, 4))
        val accelPct = u8(f, 6) * 2.0
        mapOf(
            "rpm" to rpm,
            "speed_kph" to speedKph,
            "accelerator_pct" to accelPct,
        )
    }

    /** Engine block (load, coolant, ignition advance, TPS, IAT). 10 Hz. */
    val ENGINE_240 = FrameSpec(
        id = 0x240,
        name = "engine_240",
        description = "Calculated load (B0), coolant °C (B1-40), ignition timing (B2, WIP scale), throttle valve % (B3*100/255), IAT °C (B4-40).",
        verified = true,
    ) { f ->
        if (f.data.size < 5) return@FrameSpec emptyMap()
        mapOf(
            "engine_load_pct" to (u8(f, 0) * 100.0 / 255.0),
            "coolant_c" to (u8(f, 1) - 40.0),
            "ignition_timing_raw" to u8(f, 2).toDouble(),
            "throttle_valve_pct" to (u8(f, 3) * 100.0 / 255.0),
            "iat_c" to (u8(f, 4) - 40.0),
        )
    }

    /** Throttle valve position alternate (byte 6). 100 Hz. */
    val THROTTLE_215 = FrameSpec(
        id = 0x215,
        name = "throttle_215",
        description = "Throttle valve position % (byte 6 * 100/255).",
        verified = true,
    ) { f ->
        if (f.data.size < 7) return@FrameSpec emptyMap()
        mapOf("throttle_valve_pct" to (u8(f, 6) * 100.0 / 255.0))
    }

    /** Transmission / clutch switch (MT). 40 Hz. */
    val TRANS_231 = FrameSpec(
        id = 0x231,
        name = "trans_231",
        description = "Clutch switch (byte 1 MSB) on manual transmission cars.",
        verified = true,
    ) { f ->
        if (f.data.size < 2) return@FrameSpec emptyMap()
        mapOf("clutch_switch" to msb(u8(f, 1)).toDouble())
    }

    /** Master table — every known frame. */
    val ALL: List<FrameSpec> = listOf(
        WHEEL_SPEEDS, STEERING, BRAKE, PCM_201, ENGINE_240, THROTTLE_215, TRANS_231,
    )

    val BY_ID: Map<Int, FrameSpec> = ALL.associateBy { it.id }

    /**
     * IDs the dashboard's monitor-mode subscription uses. Confirmed present on Fae's 2006 NC1
     * via the Test CAN diagnostic ("Frames seen" output). The DSC-only frames (0x081 steering,
     * 0x085 brake, 0x090 acceleration) are NOT included because this car lacks the optional
     * DSC module — they never broadcast. If you upgrade to a DSC-equipped car, append
     * STEERING.id and BRAKE.id back here.
     */
    val SubscribedIds: List<Int> = listOf(
        WHEEL_SPEEDS.id,    // 0x4B0
        PCM_201.id,         // 0x201
        ENGINE_240.id,      // 0x240
        TRANS_231.id,       // 0x231
    )
}
