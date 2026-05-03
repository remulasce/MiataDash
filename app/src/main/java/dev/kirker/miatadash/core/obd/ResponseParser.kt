package dev.kirker.miatadash.core.obd

/**
 * Stateful line buffer for ELM327-style output.
 *
 * The adapter emits ASCII bytes terminated by `\r`; a full response ends with the prompt `>`.
 * Multi-line responses (long PIDs, monitored CAN traffic) come as several `\r`-terminated lines
 * before the final `>`.
 *
 * Usage:
 *   val parser = ResponseParser()
 *   parser.feed(bytes)
 *   while (true) {
 *     val frame = parser.takeFrame() ?: break
 *     // frame is a list of lines comprising one complete response, prompt stripped
 *   }
 *
 * Or for raw line emission (CAN monitor mode), use [takeLine] which returns each `\r`-terminated
 * line as it arrives.
 */
class ResponseParser {
    private val sb = StringBuilder()
    private val lineQueue = ArrayDeque<String>()
    private val frames = ArrayDeque<List<String>>()
    private val frameAccum = mutableListOf<String>()

    fun feed(bytes: ByteArray) {
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            when (c) {
                '\r' -> flushLine()
                '\n' -> { /* ignore — ATL0 should suppress, but handle defensively */ }
                '>' -> {
                    // End-of-response prompt. Push current frame.
                    flushLine()
                    if (frameAccum.isNotEmpty()) {
                        frames.addLast(frameAccum.toList())
                        frameAccum.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun flushLine() {
        if (sb.isEmpty()) return
        val line = sb.toString().trim()
        sb.clear()
        if (line.isEmpty()) return
        lineQueue.addLast(line)
        frameAccum.add(line)
    }

    fun takeLine(): String? = lineQueue.removeFirstOrNull()

    fun takeFrame(): List<String>? = frames.removeFirstOrNull()

    fun reset() {
        sb.clear(); lineQueue.clear(); frames.clear(); frameAccum.clear()
    }
}
