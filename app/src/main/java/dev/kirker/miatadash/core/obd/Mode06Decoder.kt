package dev.kirker.miatadash.core.obd

/**
 * Mode 06 — on-board monitoring test results.
 *
 * Mode 06 is the canonical place to read catalyst efficiency test results, EGR test results,
 * misfire counts, EVAP leak detection, etc. Each result is keyed by a manufacturer-defined
 * MID (monitor ID) and a TID (test ID), reported with current value, min, and max.
 *
 * For 2008+ vehicles MIDs are standardized in SAE J1979. For 2006 NC1, MID assignments are
 * Mazda-specific and discovered by querying `0600` (supported MIDs) and probing.
 *
 * Wire format: same shape as PID/DTC responses — under ATH1+ATS0, an 11-bit CAN header
 * precedes the response signature `46`. We search the concatenated hex run for `46` and
 * iterate result rows of 9 bytes each (MID, TID, unit, val_hi, val_lo, min_hi, min_lo,
 * max_hi, max_lo).
 *
 * Real-world Mode 06 row layout varies by ECU; treat parsed rows as informational and
 * surface raw responses in the diagnostic UI as well.
 */
data class Mode06Result(
    val mid: Int,
    val tid: Int,
    val unitId: Int,
    val value: Int,
    val min: Int,
    val max: Int,
) {
    fun pretty(): String = "MID=%02X TID=%02X val=%d min=%d max=%d".format(mid, tid, value, min, max)
    fun isPass(): Boolean = value in min..max
}

object Mode06Decoder {

    fun decode(lines: List<String>): List<Mode06Result> {
        val hex = lines.joinToString("").replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        val sigIdx = hex.indexOf("46").takeIf { it >= 0 } ?: return emptyList()
        // Skip "46" sig itself; data starts at sigIdx+2 (no count byte before rows in this layout).
        var i = sigIdx + 2
        val results = mutableListOf<Mode06Result>()
        while (i + 18 <= hex.length) {       // 9 bytes × 2 chars per byte
            val bytes = List(9) { bIdx ->
                hex.substring(i + bIdx * 2, i + bIdx * 2 + 2).toIntOrNull(16) ?: return results
            }
            results.add(
                Mode06Result(
                    mid = bytes[0],
                    tid = bytes[1],
                    unitId = bytes[2],
                    value = (bytes[3] shl 8) or bytes[4],
                    min = (bytes[5] shl 8) or bytes[6],
                    max = (bytes[7] shl 8) or bytes[8]
                )
            )
            i += 18
        }
        return results
    }
}
