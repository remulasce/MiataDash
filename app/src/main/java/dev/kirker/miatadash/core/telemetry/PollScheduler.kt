package dev.kirker.miatadash.core.telemetry

import dev.kirker.miatadash.core.obd.PidSpec
import dev.kirker.miatadash.core.obd.RefreshTier

/**
 * Round-robin scheduler that respects per-tier refresh rates.
 *
 * Tier rates (target):
 *   Fast   — 10 Hz
 *   Medium —  5 Hz
 *   Slow   —  1 Hz
 *
 * Implementation: a virtual clock advances each `next()` call; we pick the PID whose
 * deadline has passed most. If no PID is due, we still return one (the soonest), since
 * it's better to keep the radio busy than idle.
 */
class PollScheduler(private val pids: List<PidSpec>) {

    private data class Slot(val spec: PidSpec, var lastDueMs: Long, val intervalMs: Long)

    private val slots = pids.map { Slot(it, lastDueMs = 0, intervalMs = intervalFor(it.refreshTier)) }
    private val startMs = System.currentTimeMillis()

    fun next(): PidSpec? {
        if (slots.isEmpty()) return null
        val now = System.currentTimeMillis() - startMs
        // Pick the slot most overdue.
        val pick = slots.maxByOrNull { now - (it.lastDueMs + it.intervalMs) } ?: return null
        pick.lastDueMs = now
        return pick.spec
    }

    private fun intervalFor(t: RefreshTier): Long = when (t) {
        RefreshTier.Fast -> 100
        RefreshTier.Medium -> 200
        RefreshTier.Slow -> 1000
    }
}
