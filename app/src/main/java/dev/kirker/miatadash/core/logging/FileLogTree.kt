package dev.kirker.miatadash.core.logging

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timber tree that writes INFO-and-above messages to a rotating set of session log files.
 *
 * ## Storage
 * `filesDir/logs/session_0.log` — current session (written live).
 * `filesDir/logs/session_1.log` — previous session.
 * …
 * `filesDir/logs/session_9.log` — oldest retained session.
 *
 * On each app launch the files rotate (0→1→…→[MAX_SESSIONS-1], oldest is deleted), so you
 * always have the last [MAX_SESSIONS] sessions available for post-drive debugging via ADB or
 * the in-app Session Logs viewer.
 *
 * ## What gets logged
 * WARN and ERROR always. INFO for key lifecycle events (connect/disconnect, events).
 * DEBUG is suppressed — at 100 Hz CAN throughput it would produce ~50 MB/session.
 *
 * ## Size cap
 * Each file is capped at [MAX_FILE_BYTES] (4 MB). Writes past the cap are silently dropped
 * to prevent runaway storage use on long drives.
 */
class FileLogTree(context: Context) : Timber.Tree() {

    private val logFile: File

    init {
        val logDir = File(context.filesDir, "logs").also { it.mkdirs() }
        rotate(logDir)
        logFile = File(logDir, "session_0.log")
        // Write a header so each session is easy to identify in the file.
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("── MiataDash session started $ts ──")
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.INFO   // DEBUG/VERBOSE excluded — too noisy at CAN rates

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (logFile.length() >= MAX_FILE_BYTES) return   // cap; don't rotate mid-session

        val level = when (priority) {
            Log.INFO  -> "I"
            Log.WARN  -> "W"
            Log.ERROR -> "E"
            else      -> "?"
        }
        val ts = TS_FORMAT.get()!!.format(Date())
        appendLine("$ts $level/$tag: $message")
        t?.let { appendLine("  ↳ ${it.stackTraceToString().trimEnd()}") }
    }

    private fun appendLine(line: String) {
        try {
            logFile.appendText(line + "\n")
        } catch (_: Exception) {
            // Can't log to file — don't recurse into Timber.
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024   // 4 MB per session
        const val MAX_SESSIONS   = 10

        /** ThreadLocal because SimpleDateFormat is not thread-safe. */
        val TS_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }

        fun rotate(logDir: File) {
            // Delete the oldest slot, then shift each file up by one index.
            // session_(MAX-1) is deleted, session_N → session_(N+1) for N in MAX-2..0.
            File(logDir, "session_${MAX_SESSIONS - 1}.log").delete()
            for (i in MAX_SESSIONS - 2 downTo 0) {
                File(logDir, "session_$i.log").renameTo(File(logDir, "session_${i + 1}.log"))
            }
        }
    }
}
