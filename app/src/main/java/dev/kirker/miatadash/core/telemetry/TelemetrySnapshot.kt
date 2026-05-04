package dev.kirker.miatadash.core.telemetry

/**
 * The current best-known telemetry values across the whole vehicle.
 *
 * `null` means "no value yet" (haven't received a response). Each value carries its own
 * last-update timestamp so the UI can render staleness if desired.
 */
data class TelemetrySnapshot(
    val tsMs: Long = 0L,

    // Standard PIDs
    val rpm: Reading<Double>? = null,
    val speedKph: Reading<Double>? = null,
    val coolantC: Reading<Double>? = null,
    val iatC: Reading<Double>? = null,
    val throttlePct: Reading<Double>? = null,
    val mafGps: Reading<Double>? = null,
    val engineLoadPct: Reading<Double>? = null,
    val timingDeg: Reading<Double>? = null,
    val batteryV: Reading<Double>? = null,
    val stftPct: Reading<Double>? = null,
    val ltftPct: Reading<Double>? = null,

    // Smog
    val o2PreV: Reading<Double>? = null,
    val o2PostV: Reading<Double>? = null,

    // Mazda CAN
    val wheelSpeeds: Reading<WheelSpeeds>? = null,
    val brakePressureKpa: Reading<Double>? = null,
    val brakePct: Reading<Double>? = null,
    val brakeSwitch: Reading<Boolean>? = null,
    val steeringRaw: Reading<Double>? = null,
    val acceleratorPct: Reading<Double>? = null,
    val clutchSwitch: Reading<Boolean>? = null,

    /**
     * Longitudinal G-force derived from 0x201 vehicle speed (positive = braking / decel,
     * negative = acceleration). Computed over a 200 ms sliding window — same approach as
     * the brake event detector but smoothed for live display.
     *
     * This is a software estimate, not a hardware sensor. Once the SRS accelerometer CAN ID
     * is confirmed via histogram, this field can be compared against the raw CAN value to
     * validate both the ID and the decode scale.
     */
    val longGForce: Reading<Double>? = null,

    /**
     * Lateral G-force derived from the rear-axle wheel-speed differential.
     *
     * Formula: `vehicleKph × (rr − rl) / (3.6² × trackWidth_m × g)`.
     * NC track width ≈ 1.4825 m → denominator ≈ 188.5.
     *
     * Positive = rightward G force (car turning left, driver pushed right).
     * Near-zero below ~10 kph where the wheel-speed differential is too noisy to use.
     */
    val latGForce: Reading<Double>? = null,

    /**
     * Raw int16 values from speculative SRS accelerometer CAN frames (0x420 / 0x430).
     * Bytes 0-1 and bytes 2-3 decoded as signed int16 big-endian. Scale and axis assignment
     * are unknown until validated on the car. Compare against [longGForce] during a known
     * braking event to derive the correct divisor.
     */
    val srsAccelRaw0: Reading<Double>? = null,
    val srsAccelRaw2: Reading<Double>? = null,
)

data class Reading<T>(val value: T, val tsMs: Long)

data class WheelSpeeds(val fl: Double, val fr: Double, val rl: Double, val rr: Double)
