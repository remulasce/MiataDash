package dev.kirker.miatadash.core.obd

/**
 * Decodes Mode 03 (current DTCs), Mode 07 (pending DTCs), and Mode 0A (permanent DTCs).
 *
 * Each DTC is a 2-byte code. The top two bits select the system letter:
 *   00 = P (Powertrain), 01 = C (Chassis), 10 = B (Body), 11 = U (Network)
 * Followed by 4 hex digits encoding the rest. Result: e.g. `0420` decoded as `P0420`
 * (catalyst efficiency below threshold).
 *
 * Wire format (CAN ISO-15765-4 single frame, ATH1+ATS0):
 *   `7E80543010133...`  →  header 7E8, length 05, response 43 01 01 33 ...
 * The byte after the 0x43/0x47/0x4A response signature is the DTC count.
 */
data class Dtc(val code: String, val raw: Int) {
    fun isClearOrPad() = raw == 0
}

object DtcDecoder {

    private val systemLetters = charArrayOf('P', 'C', 'B', 'U')

    /** Mode 03 / 07 / 0A response signatures. */
    private val responseSignatures = listOf(0x43, 0x47, 0x4A)

    fun decode(lines: List<String>): List<Dtc> {
        if (lines.isEmpty()) return emptyList()
        // Concatenate all hex characters across all response lines (multi-frame responses
        // arrive as multiple lines with a sequence number prefix; this is a v1 simplification
        // that works for single-frame responses).
        val hex = lines.joinToString("")
            .replace(Regex("[^0-9A-Fa-f]"), "")
            .uppercase()

        // Find a response signature (0x43, 0x47, or 0x4A) at any position.
        var sigIdx = -1
        for (sig in responseSignatures) {
            val target = "%02X".format(sig)
            val candidate = hex.indexOf(target)
            // Avoid matching on a CAN ID coincidentally containing "43"/etc — require there to be
            // enough characters after for at least the count byte.
            if (candidate >= 0 && candidate + 4 <= hex.length) {
                if (sigIdx < 0 || candidate < sigIdx) sigIdx = candidate
            }
        }
        if (sigIdx < 0) return emptyList()

        // Skip the response sig (2 chars) + count byte (2 chars).
        val dataStart = sigIdx + 4
        if (dataStart >= hex.length) return emptyList()

        val codes = mutableListOf<Dtc>()
        var i = dataStart
        while (i + 4 <= hex.length) {
            val word = hex.substring(i, i + 4)
            i += 4
            val v = word.toIntOrNull(16) ?: continue
            if (v == 0) continue   // padding
            val sysIdx = (v shr 14) and 0x3
            val letter = systemLetters[sysIdx]
            val rest = v and 0x3FFF
            codes.add(Dtc("%c%04X".format(letter, rest), v))
        }
        return codes
    }

    /**
     * Curated descriptions for codes the user is likely to see on an NC1. Falls back to a
     * generic message for codes not in the table. Add codes as you encounter them.
     */
    fun describe(code: String): String = DESCRIPTIONS[code] ?: "Unknown — see SAE J2012 / Mazda service literature"

    private val DESCRIPTIONS = mapOf(
        "P0030" to "HO2S heater control circuit (Bank 1 Sensor 1)",
        "P0031" to "HO2S heater control circuit low (B1S1)",
        "P0032" to "HO2S heater control circuit high (B1S1)",
        "P0036" to "HO2S heater control circuit (B1S2)",
        "P0050" to "HO2S heater control circuit (B2S1)",
        "P0100" to "MAF circuit",
        "P0101" to "MAF circuit range/performance",
        "P0102" to "MAF circuit low input",
        "P0103" to "MAF circuit high input",
        "P0113" to "IAT circuit high input",
        "P0117" to "ECT circuit low input",
        "P0118" to "ECT circuit high input",
        "P0125" to "Insufficient coolant temp for closed-loop fuel control",
        "P0128" to "Coolant thermostat (below regulating temperature)",
        "P0130" to "O2 sensor circuit (B1S1)",
        "P0133" to "O2 sensor slow response (B1S1) — common 'lazy O2' code",
        "P0134" to "O2 sensor circuit no activity (B1S1)",
        "P0136" to "O2 sensor circuit (B1S2)",
        "P0137" to "O2 sensor low voltage (B1S2)",
        "P0138" to "O2 sensor high voltage (B1S2)",
        "P0139" to "O2 sensor slow response (B1S2)",
        "P0140" to "O2 sensor no activity (B1S2)",
        "P0171" to "System too lean (B1)",
        "P0172" to "System too rich (B1)",
        "P0300" to "Random/multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire",
        "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire",
        "P0327" to "Knock sensor low input",
        "P0335" to "Crankshaft position sensor",
        "P0340" to "Camshaft position sensor",
        "P0420" to "Catalyst system efficiency below threshold (B1) — the smog killer",
        "P0440" to "Evaporative emission system",
        "P0441" to "EVAP purge flow incorrect",
        "P0442" to "EVAP small leak detected (often gas cap)",
        "P0455" to "EVAP large leak detected",
        "P0500" to "Vehicle speed sensor",
        "P0505" to "Idle air control",
        "P0700" to "TCM transmission control system",
    )
}
