package dev.streetsnap.app

/** A model answer may describe an old view. Only fresh answers from the current view can be drawn. */
internal class CompositionResultGate(private val maximumAgeMs: Long = 5_000L) {
    data class Ticket(val generation: Long, val frameAtMs: Long)
    private var generation = 0L
    private var invalidatedAtMs = 0L

    @Synchronized fun invalidate(nowMs: Long) { generation++; invalidatedAtMs = nowMs }
    @Synchronized fun ticket(frameAtMs: Long): Ticket? =
        if (frameAtMs < invalidatedAtMs) null else Ticket(generation, frameAtMs)

    /**
     * A conflated frame may have waited behind a slow inference. Check its age before
     * starting another model call so stale work is discarded without spending CPU on an
     * answer that [accepts] would reject anyway.
     */
    @Synchronized fun isFreshForInference(ticket: Ticket, nowMs: Long): Boolean =
        ticket.generation == generation && nowMs >= ticket.frameAtMs &&
            nowMs - ticket.frameAtMs < maximumAgeMs

    /** Remaining wall-clock budget before the answer would be rejected as stale. */
    @Synchronized fun remainingMs(ticket: Ticket, nowMs: Long): Long =
        if (ticket.generation != generation) 0L
        else (maximumAgeMs - (nowMs - ticket.frameAtMs)).coerceAtLeast(0L)

    @Synchronized fun accepts(ticket: Ticket, nowMs: Long, frameIsCurrent: Boolean): Boolean =
        frameIsCurrent && isFreshForInference(ticket, nowMs)

    fun expiresAt(ticket: Ticket): Long = ticket.frameAtMs + maximumAgeMs
}
