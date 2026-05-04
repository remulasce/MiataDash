package dev.kirker.miatadash.core.braking

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists completed [BrakeEvent]s to internal storage as JSON files and reloads them later.
 *
 * Storage location: `filesDir/brake_logs/brake_<id>.json`
 *
 * No external permissions are needed — [Context.getFilesDir] is private app storage.
 *
 * Capacity: at most [MAX_STORED_FILES] files are kept. When the limit is exceeded the oldest
 * files (lowest event ID = earliest timestamp) are deleted automatically.
 *
 * Serialization uses [org.json] (always available in Android) and is intentionally verbose so
 * the files are human-readable for post-session analysis.
 *
 * Thread-safety: all public methods are synchronised on `this`. [logEvent] is typically called
 * from the CAN collector coroutine; [loadAll] and [delete] from the UI coroutine. They are
 * infrequent (one file per braking event) so a simple lock is sufficient.
 */
@Singleton
class BrakeEventLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logDir: File by lazy {
        File(context.filesDir, "brake_logs").also { it.mkdirs() }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Serialises [event] to JSON and writes it to `brake_<event.id>.json`. If the directory
     * would then exceed [MAX_STORED_FILES], the oldest files are trimmed.
     */
    @Synchronized
    fun logEvent(event: BrakeEvent) {
        try {
            val file = fileFor(event.id)
            file.writeText(event.toJson().toString(2))
            Timber.i("BrakeLogger: saved %s (%.1f→%.1f kph)", file.name, event.startSpeedKph, event.endSpeedKph)
            trimToCapacity()
        } catch (e: Exception) {
            Timber.e(e, "BrakeLogger: failed to write event %d", event.id)
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns all persisted events, most-recent-first. Any file that fails to parse is silently
     * skipped (corrupted file from crash, etc.).
     */
    @Synchronized
    fun loadAll(): List<BrakeEvent> {
        val files = logDir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.name }   // brake_<id>.json — lexicographic desc = newest first
            .mapNotNull { f ->
                try {
                    BrakeEvent.fromJson(JSONObject(f.readText()))
                } catch (e: Exception) {
                    Timber.w(e, "BrakeLogger: skipping unparseable file %s", f.name)
                    null
                }
            }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Deletes the persisted file for [id]. No-op if the file doesn't exist. */
    @Synchronized
    fun delete(id: Long) {
        val f = fileFor(id)
        if (f.delete()) Timber.i("BrakeLogger: deleted %s", f.name)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun fileFor(id: Long) = File(logDir, "brake_%020d.json".format(id))

    private fun trimToCapacity() {
        val files = (logDir.listFiles { f -> f.name.endsWith(".json") } ?: return)
            .sortedBy { it.name }   // ascending = oldest first
        val excess = files.size - MAX_STORED_FILES
        if (excess > 0) {
            files.take(excess).forEach { f ->
                f.delete()
                Timber.d("BrakeLogger: trimmed %s", f.name)
            }
        }
    }

    private companion object {
        const val MAX_STORED_FILES = 50
    }
}

// ── JSON serialization helpers ─────────────────────────────────────────────────────────────

private fun BrakeEvent.toJson(): JSONObject = JSONObject().apply {
    put("id",            id)
    put("startMs",       startMs)
    put("endMs",         endMs)
    put("startSpeedKph", startSpeedKph)
    put("endSpeedKph",   endSpeedKph)
    put("fl",            fl.toJson())
    put("fr",            fr.toJson())
    put("rl",            rl.toJson())
    put("rr",            rr.toJson())
    srsRaw0?.let { put("srsRaw0", it.toJson()) }
    srsRaw2?.let { put("srsRaw2", it.toJson()) }
    put("samples",       JSONArray().also { arr ->
        samples.forEach { s -> arr.put(s.toJson()) }
    })
}

private fun SrsRawStats.toJson(): JSONObject = JSONObject().apply {
    put("min",   min)
    put("max",   max)
    put("avg",   avg)
    put("count", count)
}

private fun CornerSlipStats.toJson(): JSONObject = JSONObject().apply {
    put("avgSlipKph",  avgSlipKph)
    put("peakSlipKph", peakSlipKph)
    put("absPulses",   absPulses)
}

private fun BrakeSample.toJson(): JSONObject = JSONObject().apply {
    put("tsMs",       tsMs)
    put("fl",         fl)
    put("fr",         fr)
    put("rl",         rl)
    put("rr",         rr)
    put("vehicleKph", vehicleKph)
}

private fun BrakeEvent.Companion.fromJson(j: JSONObject): BrakeEvent = BrakeEvent(
    id            = j.getLong("id"),
    startMs       = j.getLong("startMs"),
    endMs         = j.getLong("endMs"),
    startSpeedKph = j.getDouble("startSpeedKph"),
    endSpeedKph   = j.getDouble("endSpeedKph"),
    fl            = CornerSlipStats.fromJson(j.getJSONObject("fl")),
    fr            = CornerSlipStats.fromJson(j.getJSONObject("fr")),
    rl            = CornerSlipStats.fromJson(j.getJSONObject("rl")),
    rr            = CornerSlipStats.fromJson(j.getJSONObject("rr")),
    // optJSONObject returns null for older files that predate SRS logging — backward-compatible.
    srsRaw0       = j.optJSONObject("srsRaw0")?.let { SrsRawStats.fromJson(it) },
    srsRaw2       = j.optJSONObject("srsRaw2")?.let { SrsRawStats.fromJson(it) },
    samples       = buildList {
        val arr = j.getJSONArray("samples")
        repeat(arr.length()) { i -> add(BrakeSample.fromJson(arr.getJSONObject(i))) }
    },
)

private fun SrsRawStats.Companion.fromJson(j: JSONObject) = SrsRawStats(
    min   = j.getDouble("min"),
    max   = j.getDouble("max"),
    avg   = j.getDouble("avg"),
    count = j.getInt("count"),
)

private fun CornerSlipStats.Companion.fromJson(j: JSONObject) = CornerSlipStats(
    avgSlipKph  = j.getDouble("avgSlipKph"),
    peakSlipKph = j.getDouble("peakSlipKph"),
    absPulses   = j.getInt("absPulses"),
)

private fun BrakeSample.Companion.fromJson(j: JSONObject) = BrakeSample(
    tsMs       = j.getLong("tsMs"),
    fl         = j.getDouble("fl"),
    fr         = j.getDouble("fr"),
    rl         = j.getDouble("rl"),
    rr         = j.getDouble("rr"),
    vehicleKph = j.getDouble("vehicleKph"),
)
