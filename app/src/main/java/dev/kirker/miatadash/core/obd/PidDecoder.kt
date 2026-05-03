package dev.kirker.miatadash.core.obd

/** Result of parsing one Mode 01 response line. */
sealed interface PidResponse {
    data class Ok(val pid: Int, val raw: ByteArray, val value: Double, val unit: String) : PidResponse
    data class Unsupported(val pid: Int) : PidResponse
    data class NoData(val pid: Int) : PidResponse
    data class Garbled(val pid: Int, val rawLine: String) : PidResponse
}

object PidDecoder {

    /**
     * Decodes one Mode 01 response line. Tolerant of multiple ELM output formats:
     *
     *   With ATH1 + ATS0 (headers on, spaces off — our default):
     *     `7E804410C0ECF`   → header 7E8, length 04, response 41 0C 0E CF (RPM = 947)
     *
     *   With ATH1 + ATS1 (headers on, spaces on):
     *     `7E8 04 41 0C 0E CF`
     *
     *   With ATH0 + ATS1 (no headers):
     *     `41 0C 0E CF`
     *
     *   With ATH0 + ATS0:
     *     `410C0ECF`
     *
     *   Adapter chatter:
     *     `NO DATA`, `?`, `STOPPED`, `UNABLE TO CONNECT`
     *
     * Strategy: strip non-hex characters, search the resulting string for the response
     * signature `41<PID>`, and read [PidSpec.numBytes] data bytes from immediately after it.
     * This is robust to whether headers are present, whether spaces are present, and to
     * the 11-bit CAN header introducing odd-length runs.
     */
    fun decode(line: String, expectedPid: Int): PidResponse {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return PidResponse.Garbled(expectedPid, trimmed)
        if (trimmed.equals("NO DATA", ignoreCase = true)) return PidResponse.NoData(expectedPid)
        if (trimmed == "?" ||
            trimmed.startsWith("UNABLE", ignoreCase = true) ||
            trimmed.startsWith("STOPPED", ignoreCase = true) ||
            trimmed.startsWith("BUS", ignoreCase = true) ||      // BUS INIT, BUS BUSY
            trimmed.startsWith("CAN ERROR", ignoreCase = true)
        ) {
            return PidResponse.Garbled(expectedPid, trimmed)
        }

        val spec = Pid.All[expectedPid] ?: return PidResponse.Unsupported(expectedPid)

        val hex = trimmed.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        val sig = "41%02X".format(expectedPid)
        val sigIdx = hex.indexOf(sig)
        if (sigIdx < 0) return PidResponse.Garbled(expectedPid, trimmed)

        val dataStart = sigIdx + sig.length
        val dataEnd = dataStart + spec.numBytes * 2
        if (dataEnd > hex.length) return PidResponse.Garbled(expectedPid, trimmed)

        val data = ByteArray(spec.numBytes) { i ->
            hex.substring(dataStart + i * 2, dataStart + i * 2 + 2).toInt(16).toByte()
        }
        val value = spec.decode(data)
        return PidResponse.Ok(expectedPid, data, value, spec.unit)
    }
}
