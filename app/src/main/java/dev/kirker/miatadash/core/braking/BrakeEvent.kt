package dev.kirker.miatadash.core.braking

/**
 * Statistics for one wheel corner over a completed braking event.
 *
 * Slip values are in kph; negative means the wheel was slower than the ECU vehicle speed
 * (i.e. the wheel was locking up). Zero = no slip; large negative = heavy lock-up.
 */
data class CornerSlipStats(
    /** Mean slip across the event (kph, ≤ 0). */
    val avgSlipKph: Double,
    /** Worst (most negative) instantaneous slip observed (kph). */
    val peakSlipKph: Double,
    /** Number of times ABS released this corner (wheel recovered above release threshold). */
    val absPulses: Int,
) { companion object }

/**
 * One high-frequency wheel-speed sample captured during a braking event.
 *
 * [vehicleKph] is the ECU speed from 0x201 at the same instant. Storing it per-sample
 * means the delta graph is historically correct even as the car decelerates across the event.
 */
data class BrakeSample(
    val tsMs: Long,
    val fl: Double,      // kph
    val fr: Double,
    val rl: Double,
    val rr: Double,
    val vehicleKph: Double,
) { companion object }

/**
 * Min / max / average of a raw SRS accelerometer int16 channel logged during one braking
 * event. Used to calibrate the scale factor once we know the expected G-force from the
 * derived LONG G reading.
 *
 * **To calibrate**: divide [avg] (or [max] for peak decel) by the simultaneously-derived
 * longitudinal G reading (from [BrakeEvent.startSpeedKph] and duration) to get LSB/g.
 * Confirm with multiple events — the ratio should be consistent.
 */
data class SrsRawStats(
    val min: Double,
    val max: Double,
    val avg: Double,
    val count: Int,
) { companion object }

/**
 * A completed hard-braking event.
 *
 * [samples] contains the full high-frequency record during the event **plus** a short
 * pre-event context window so the snapshot graph shows the car's state entering the stop.
 *
 * [srsRaw0] and [srsRaw2] are populated if 0x430 (SRS accelerometer, confirmed on Fae's
 * NC1) was broadcasting during the event. These are raw int16 values — bytes 0-1 and
 * bytes 2-3 of the frame — with scale unknown until calibrated from a known-G stop.
 * Bytes 2-3 (~0 at rest) are believed to be the longitudinal axis.
 */
data class BrakeEvent(
    /** Unique stable ID — reuse [endMs] since events can't overlap. */
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    /** ECU vehicle speed at the moment braking was first detected (kph). */
    val startSpeedKph: Double,
    /** ECU vehicle speed when deceleration fully cleared (kph). */
    val endSpeedKph: Double,
    /** Raw high-frequency samples (pre-event buffer + event). */
    val samples: List<BrakeSample>,
    val fl: CornerSlipStats,
    val fr: CornerSlipStats,
    val rl: CornerSlipStats,
    val rr: CornerSlipStats,
    /** SRS raw channel 0 (bytes 0-1) stats, null if 0x430 wasn't seen. */
    val srsRaw0: SrsRawStats? = null,
    /** SRS raw channel 2 (bytes 2-3) stats, null if 0x430 wasn't seen. */
    val srsRaw2: SrsRawStats? = null,
) {
    val durationMs: Long get() = endMs - startMs

    /**
     * Time span from the first sample to the last, used to size the snapshot graph's x-axis.
     * Falls back to 5 s if the sample list is empty.
     */
    val sampleWindowMs: Long
        get() = if (samples.size >= 2) samples.last().tsMs - samples.first().tsMs + 200L else 5_000L

    /**
     * One-line plain-English summary of the slip pattern observed during this event.
     *
     * Detection thresholds:
     *   Significant slip : peak < −3 kph (ABS territory)
     *   Heavy slip        : peak < −5 kph (clear lock-up)
     *
     * Patterns checked in priority order:
     *   1. All four corners slipped
     *   2. Both axles (front or rear) slipped symmetrically
     *   3. One side (left or right) dominated
     *   4. Individual corner(s)
     *   5. ABS-only (pulses but no significant slip)
     *   6. Clean — no notable slip
     */
    fun slipSummary(): String {
        data class Corner(val name: String, val stats: CornerSlipStats)
        val corners = listOf(Corner("FL", fl), Corner("FR", fr), Corner("RL", rl), Corner("RR", rr))

        val slipped     = corners.filter { it.stats.peakSlipKph < -3.0 }
        val heavySlip   = corners.filter { it.stats.peakSlipKph < -5.0 }
        val totalAbs    = fl.absPulses + fr.absPulses + rl.absPulses + rr.absPulses
        val names       = slipped.map { it.name }.toSet()
        val worst       = corners.minByOrNull { it.stats.peakSlipKph }

        val pattern = when {
            names.size == 4                              -> "All four corners slipped"
            "FL" in names && "FR" in names
                && "RL" !in names && "RR" !in names     -> "Front-axle slip"
            "RL" in names && "RR" in names
                && "FL" !in names && "FR" !in names     -> "Rear-axle slip"
            "FL" in names && "RL" in names
                && "FR" !in names && "RR" !in names     -> "Left-side bias"
            "FR" in names && "RR" in names
                && "FL" !in names && "RL" !in names     -> "Right-side bias"
            slipped.size == 1                            -> "${slipped.first().name} slipped"
            slipped.size > 1                             -> slipped.joinToString(", ") { it.name } + " slipped"
            totalAbs > 0                                 -> "ABS kept all wheels rolling"
            else                                         -> "Clean — minimal slip"
        }

        return buildString {
            append(pattern)
            if (totalAbs > 0 && slipped.isNotEmpty()) append(" · ABS $totalAbs×")
            if (worst != null && worst.stats.peakSlipKph < -5.0) {
                append(" · ${worst.name} peak %.1f kph".format(worst.stats.peakSlipKph))
            }
        }
    }

    companion object
}
