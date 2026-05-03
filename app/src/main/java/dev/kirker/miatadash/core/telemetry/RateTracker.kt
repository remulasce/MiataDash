package dev.kirker.miatadash.core.telemetry

/**
 * Counts events per named source in a rolling time window. Used by [TelemetryRepository] to
 * compute live update rates for the dashboard's stats panel — e.g. how often we're seeing
 * `0x4B0` wheel-speed broadcasts vs how often the PID burst polls MAF.
 *
 * Not thread-safe — intended to be called from a single coroutine. The [TelemetryRepository]
 * routes both CAN folds and PID folds through the same poll/fold coroutine paths, so this
 * holds in practice.
 */
class RateTracker(private val windowMs: Long = 5_000L) {
    private val timestamps = mutableMapOf<String, ArrayDeque<Long>>()

    fun record(key: String, tsMs: Long = System.currentTimeMillis()) {
        val q = timestamps.getOrPut(key) { ArrayDeque() }
        q.addLast(tsMs)
        prune(q, tsMs - windowMs)
    }

    /** Snapshot of current per-source rates (events/second over the window). */
    fun snapshot(): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs
        val seconds = windowMs / 1000.0
        return timestamps.mapValues { (_, q) ->
            prune(q, cutoff)
            q.size.toDouble() / seconds
        }
    }

    fun reset() = timestamps.clear()

    private fun prune(q: ArrayDeque<Long>, cutoff: Long) {
        while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
    }
}
