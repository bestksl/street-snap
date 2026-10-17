package dev.streetsnap.app

/** A model answer may describe an old view. Only fresh answers from the current view can be drawn. */
internal class CompositionResultGate(private val maximumAgeMs: Long = 5_000L) {
    data class Ticket(val generation: Long, val frameAtMs: Long)
    private var generation = 0L
    private var invalidatedAtMs = 0L

    @Synchronized fun invalidate(nowMs: Long) { generation++; invalidatedAtMs = nowMs }
    @Synchronized fun ticket(frameAtMs: Long): Ticket? =
        if (frameAtMs < invalidatedAtMs) null else Ticket(generation, frameAtMs)

    @Synchronized fun accepts(ticket: Ticket, nowMs: Long, frameIsCurrent: Boolean): Boolean =
        frameIsCurrent && ticket.generation == generation &&
            nowMs >= ticket.frameAtMs && nowMs - ticket.frameAtMs < maximumAgeMs

    fun expiresAt(ticket: Ticket): Long = ticket.frameAtMs + maximumAgeMs
}
