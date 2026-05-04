package dev.kirker.miatadash.core.transport

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replays a captured trace file as a synthetic OBD adapter.
 *
 * Strategy: parse the trace into (command, response) pairs and serve responses on demand,
 * matched by command. This is more useful than literal cadence playback because the new
 * session's command order doesn't have to match the recorded order — when the dashboard's
 * poll loop asks for `010C` it always gets the recorded RPM response, even if `010C`
 * happened to be the 17th query in the original session.
 *
 * For commands not in the trace (e.g. the `ATZ` init that lived before the SharedFlow's
 * replay window started capturing), we synthesize benign `OK` / `NO DATA` responses so
 * the protocol FSM proceeds through initialization without stalling.
 *
 * Two file formats accepted:
 *
 *   Native `.miatatrace` (newline-delimited, our own format):
 *       <millis-since-start> <direction:R|W> <ascii-line>
 *
 *   Torque Pro CSV — best-effort. We read the timestamped raw column and treat every row as
 *   an inbound `R` event. Suitable for sniffing CAN traffic but less useful for command
 *   matching.
 */
@Singleton
class ReplayTransport @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Transport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var file: File? = null

    /** command (uppercase, normalized) → ring of recorded responses. Cycles when exhausted. */
    private var responses: Map<String, ArrayDeque<String>> = emptyMap()
    private val pending = ArrayDeque<String>()

    val tracesDir: File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir,
        "traces"
    ).apply { if (!exists()) mkdirs() }

    override val displayName: String
        get() = "Replay: ${file?.name ?: "(no file)"}"

    fun setFile(file: File) {
        this.file = file
        val events = parseEvents(file)
        responses = pairCommandsToResponses(events)
        Timber.i(
            "Replay loaded: ${responses.size} unique commands, " +
                "${responses.values.sumOf { it.size }} total responses, from ${file.name}"
        )
    }

    /** Picks the newest `.miatatrace` from [tracesDir]; returns false if none found. */
    fun autoSelectLatest(): Boolean {
        val candidates = tracesDir.listFiles { _, name -> name.endsWith(".miatatrace", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        val newest = candidates.maxByOrNull { it.lastModified() } ?: return false
        setFile(newest)
        return true
    }

    override suspend fun open() {
        if (file == null) autoSelectLatest()
        if (file == null) {
            error(
                "No trace files found in ${tracesDir.absolutePath}. " +
                    "Capture one via Diagnostics → Trace Capture, or push a .miatatrace file there via adb."
            )
        }
        if (responses.isEmpty()) {
            error(
                "Trace ${file?.name} parsed to zero command/response pairs. " +
                    "Either the file is empty or it's a Torque Pro CSV without paired writes."
            )
        }
        _state.value = TransportState.Open
    }

    override suspend fun close() {
        _state.value = TransportState.Closed
    }

    override fun incoming(): Flow<ByteArray> = channelFlow {
        // Initial banner — what the user would see on a real adapter power-up.
        send("ELM327 v1.5 (replay)\r\r>".toByteArray())
        while (!isClosedForSend && _state.value is TransportState.Open) {
            while (pending.isNotEmpty()) {
                val r = pending.removeFirst()
                delay(25)                  // realistic adapter turnaround
                send("$r\r>".toByteArray())
            }
            delay(10)
        }
        awaitClose { }
    }

    override suspend fun write(bytes: ByteArray) {
        val cmd = bytes.toString(Charsets.US_ASCII).trim().uppercase()
        pending.addLast(takeNextResponse(cmd))
    }

    /**
     * Looks up the recorded response for [cmd]. The queue cycles, so re-asking the same
     * PID over and over walks through the recorded history (you'll see RPM gently vary as
     * the trace's recorded values play back).
     *
     * For commands that were never recorded (e.g. `ATZ` from an earlier session), returns
     * a synthetic adapter-shaped response so init doesn't stall.
     */
    private fun takeNextResponse(cmd: String): String {
        val q = responses[cmd]
        if (q != null && q.isNotEmpty()) {
            val r = q.removeFirst()
            q.addLast(r)
            return r
        }
        return when {
            cmd == "ATZ" -> "ELM327 v1.5 (replay)"
            cmd == "ATRV" -> "12.6V"
            cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)"
            cmd.startsWith("AT") -> "OK"
            cmd.startsWith("ST") -> "OK"
            else -> "NO DATA"
        }
    }

    private data class TraceEvent(val tsMs: Long, val direction: Char, val line: String)

    private fun parseEvents(file: File): List<TraceEvent> {
        if (!file.exists()) return emptyList()
        val firstLine = file.useLines { it.firstOrNull().orEmpty() }
        return when {
            firstLine.contains(",") -> parseTorqueCsv(file)
            else -> parseNative(file)
        }
    }

    private fun parseNative(file: File): List<TraceEvent> = file.useLines { lines ->
        lines.mapNotNull { raw ->
            val parts = raw.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
            val dir = parts[1].firstOrNull()?.uppercaseChar() ?: return@mapNotNull null
            if (dir != 'R' && dir != 'W') return@mapNotNull null
            TraceEvent(ts, dir, parts[2])
        }.toList()
    }

    private fun parseTorqueCsv(file: File): List<TraceEvent> = file.useLines { lines ->
        // Torque Pro CSVs don't pair writes/reads. Treat every parsed row as an inbound
        // line; in command-aware replay this means they only surface for the (rare) command
        // that happens to match — the use case is mostly sniffing, not dashboard exercising.
        val rows = lines.toList()
        if (rows.size < 2) return@useLines emptyList()
        val header = rows[0].split(",").map { it.trim().lowercase() }
        val tsIdx = header.indexOfFirst { "time" in it || "millis" in it }.takeIf { it >= 0 } ?: 0
        val rawIdx = header.indexOfFirst { "raw" in it || "hex" in it || "data" in it }
            .takeIf { it >= 0 } ?: (header.size - 1)
        var origin = -1L
        rows.asSequence().drop(1).mapNotNull { row ->
            val cols = row.split(",")
            if (cols.size <= rawIdx) return@mapNotNull null
            val ts = cols.getOrNull(tsIdx)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            if (origin < 0) origin = ts
            val line = cols[rawIdx].trim().trim('"')
            if (line.isEmpty()) return@mapNotNull null
            TraceEvent(ts - origin, 'R', line)
        }.toList()
    }

    /**
     * Walks event list pairing each W (command) with all R (responses) before the next W.
     * That handles multi-line responses too — they all queue under the same command.
     * Orphan R events at the start (commands sent before capture began) are dropped.
     */
    private fun pairCommandsToResponses(events: List<TraceEvent>): Map<String, ArrayDeque<String>> {
        val out = mutableMapOf<String, ArrayDeque<String>>()
        var currentCmd: String? = null
        for (e in events) {
            when (e.direction) {
                'W' -> currentCmd = e.line.trim().uppercase()
                'R' -> currentCmd?.let { cmd ->
                    out.getOrPut(cmd) { ArrayDeque() }.add(e.line)
                }
            }
        }
        return out
    }
}
