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
)

data class Reading<T>(val value: T, val tsMs: Long)

data class WheelSpeeds(val fl: Double, val fr: Double, val rl: Double, val rr: Double)
