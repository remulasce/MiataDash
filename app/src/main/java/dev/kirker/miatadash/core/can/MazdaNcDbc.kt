package dev.kirker.miatadash.core.can

/**
 * Mazda NC1 (2006-2008 MX-5) CAN frame map.
 *
 * Source: timurrrr/RaceChronoDiyBleDevice — `can_db/mazda_mx5_nc.md`. Cross-referenced with
 * Miata.net community work (Leisurehound). Entries here are marked `verified = true` because
 * the user has been running these formulas in Torque Pro and confirms they read sane values.
 *
 * ## Caveats specific to Fae's 2006 NC1 (no DSC option)
 *
 *  - `0x081` (steering angle), `0x085` (brake pressure), and `0x090` (lateral/longitudinal G)
 *    are broadcast by the DSC (Dynamic Stability Control) module. DSC was optional in 2006
 *    and is NOT fitted to this car. These IDs never appear on this bus. Confirmed absent via
 *    CAN histogram: no frames at any of these IDs with an unfiltered 60-second capture.
 *
 *  - `0x090` specifically: community documentation attributes this ID to the DSC's built-in
 *    accelerometer (lateral G, longitudinal G, yaw rate — similar to what a G-meter needs).
 *    Without DSC, this ID doesn't exist on this car. It is NOT the SRS (airbag) module —
 *    the SRS ECU does contain an accelerometer, but it uses an entirely different (and
 *    undocumented) CAN ID. Use the histogram tool to discover what the SRS module actually
 *    broadcasts: start CAN monitor with empty filter, wait 30 s, look for unknown amber IDs
 *    at plausible Hz rates (10-100 Hz). Candidate IDs per community research: 0x420, 0x430.
 *    Once the correct ID is found, add a FrameSpec here.
 *
 *  - `0x4B0` (wheel speeds), `0x201` (engine), `0x215`/`0x240` (engine block), and `0x231`
 *    (transmission/clutch) originate from modules present on every NC. They are always
 *    available.
 *
 * Update frequencies (per RaceChrono): most frames broadcast at 100 Hz; `0x240` at 10 Hz;
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

    /**
     * Steering angle sensor (DSC module only). 100 Hz.
     *
     * NOT present on Fae's 2006 NC1 — no DSC module fitted.
     */
    val STEERING = FrameSpec(
        id = 0x081,
        name = "steering_angle",
        description = "Steering angle, signed int16 big-endian at bytes 2-3. DSC module required — absent on non-DSC 2006 NC1.",
        verified = true,
    ) { f ->
        if (f.data.size < 4) return@FrameSpec emptyMap()
        mapOf("steering_raw" to s16(f, 2).toDouble())
    }

    /**
     * Brake pressure sensor (DSC module only). 100 Hz.
     *
     * NOT present on Fae's 2006 NC1 — no DSC module fitted. The brake detector uses
     * deceleration from 0x201 as a substitute — it measures stopping performance directly
     * and works equally well without this sensor.
     */
    val BRAKE = FrameSpec(
        id = 0x085,
        name = "brake",
        description = "Brake pressure (bytes 0-1 uint16) and switch (byte 2 MSB). DSC module required — absent on non-DSC 2006 NC1.",
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

    /**
     * SRS (airbag) module accelerometer — **confirmed broadcasting at 0x430** on Fae's 2006 NC1.
     *
     * At-rest observation: `66 58 00 00 00 00 00 00`
     *   Bytes 0-1 (ch0): 0x6658 = 26,200 raw (varies 26k–32k with engine running / slight tilt)
     *   Bytes 2-3 (ch2): 0x0000 = ~0 raw  ← near-zero at rest ✓
     *
     * **Axis assignment (provisional)**:
     *   - Ch0 (bytes 0-1): likely a non-longitudinal axis (vertical or lateral) — reads a large
     *     non-zero value at rest consistent with Earth's 1 g pulling down. Expect minimal change
     *     during straight-line braking.
     *   - Ch2 (bytes 2-3): likely **longitudinal G** — ≈0 at rest, will change during braking.
     *     Watch the max/avg in [BrakeEvent.srsRaw2] from hard stops to calibrate.
     *
     * **Scale calibration**: divide the peak ch2 change from rest by the simultaneously-derived
     * LONG G reading (from TelemetrySnapshot.longGForce) to get LSB/g. Typical SRS sensor ranges
     * are ±2 g or ±4 g; if ch2 peaks around 7000–16000 during a 0.9 g stop, the divisor is
     * roughly 7777–18000 LSB/g. Log a few hard stops with the Brake Log screen and compare.
     *
     * Bytes 4-7 are consistently 0x00 and appear unused.
     */
    val SRS_ACCEL_430 = FrameSpec(
        id = 0x430,
        name = "srs_accel_430",
        description = "SRS accelerometer (confirmed ID). Ch0=bytes 0-1, Ch2=bytes 2-3, both signed int16. Ch2≈0 at rest (longitudinal). Scale TBD — see BrakeEvent.srsRaw2 from calibration stops.",
        verified = true,   // ID confirmed; scale and axis assignment pending calibration
    ) { f ->
        if (f.data.size < 4) return@FrameSpec emptyMap()
        mapOf(
            "accel_raw_0" to s16(f, 0).toDouble(),
            "accel_raw_2" to s16(f, 2).toDouble(),
        )
    }

    /**
     * Alternate SRS accelerometer candidate — ID 0x420. **NOT confirmed** on Fae's NC1
     * (0x430 was the one found in the histogram). Kept in BY_ID so it appears as named
     * rather than "⚠ unknown" if it ever shows up, and can be distinguished from truly
     * unknown modules.
     */
    val SRS_ACCEL_420 = FrameSpec(
        id = 0x420,
        name = "srs_accel_candidate_420",
        description = "Alternate SRS accelerometer candidate (unconfirmed). 0x430 is the confirmed ID on the tested NC1.",
        verified = false,
    ) { f ->
        if (f.data.size < 4) return@FrameSpec emptyMap()
        mapOf(
            "accel_raw_0" to s16(f, 0).toDouble(),
            "accel_raw_2" to s16(f, 2).toDouble(),
        )
    }

    /** Master table — every known frame. */
    val ALL: List<FrameSpec> = listOf(
        WHEEL_SPEEDS, STEERING, BRAKE, PCM_201, ENGINE_240, THROTTLE_215, TRANS_231,
        SRS_ACCEL_420, SRS_ACCEL_430,
    )

    val BY_ID: Map<Int, FrameSpec> = ALL.associateBy { it.id }

    /**
     * IDs the dashboard's monitor-mode subscription uses. Confirmed present on Fae's 2006 NC1
     * via the CAN histogram diagnostic. DSC-only frames (0x081 steering, 0x085 brake pressure,
     * 0x090 G-sensor) are excluded because the car has no DSC module.
     *
     * **G-meter roadmap**: the SRS module likely carries an accelerometer for crash detection.
     * Once its CAN ID is empirically confirmed via the histogram tool (candidates: 0x420,
     * 0x430), add a FrameSpec above and append its ID here to start receiving it in the
     * dashboard's regular fold.
     */
    val SubscribedIds: List<Int> = listOf(
        WHEEL_SPEEDS.id,    // 0x4B0
        PCM_201.id,         // 0x201
        ENGINE_240.id,      // 0x240
        TRANS_231.id,       // 0x231
        SRS_ACCEL_430.id,   // 0x430 — SRS accelerometer, confirmed broadcasting on Fae's NC1
    )
}
