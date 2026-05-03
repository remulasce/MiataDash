package dev.kirker.miatadash.core.can

/** A parsed CAN frame as emitted by the OBDLink in `STM` monitor mode. */
data class CanFrame(
    val id: Int,
    val data: ByteArray,
    val tsMs: Long,
) {
    fun byte(i: Int): Int = data[i].toInt() and 0xFF
    fun word(hi: Int, lo: Int): Int = (byte(hi) shl 8) or byte(lo)
}

/**
 * Parses one line of `STM` output into a [CanFrame].
 *
 * The STN1110 emits monitor-mode lines in two shapes depending on `ATS` state:
 *
 *  ATS1 (spaces on, default for many adapters):
 *      `4B0 12 34 56 78 9A BC DE F0`
 *
 *  ATS0 (spaces off, what our init sequence sets):
 *      `4B0123456789ABCDEF0`        ← 3-char ID then concatenated bytes, no separators
 *
 * We strip non-hex characters first, take the first 3 hex chars as the 11-bit ID, and chunk
 * the remainder into bytes. This handles both shapes uniformly. Extended (29-bit) IDs aren't
 * expected on the NC1 OBD bus.
 */
object CanFrameParser {
    fun parse(line: String, tsMs: Long): CanFrame? {
        val cleaned = line.trim().replace(Regex("[^0-9A-Fa-f]"), "")
        if (cleaned.length < 5) return null   // need at least 3-char ID + one data byte
        if ((cleaned.length - 3) % 2 != 0) return null

        val id = cleaned.substring(0, 3).toIntOrNull(16) ?: return null
        val dataHex = cleaned.substring(3)
        val bytes = ByteArray(dataHex.length / 2) { i ->
            dataHex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        return CanFrame(id, bytes, tsMs)
    }
}
