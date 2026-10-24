package dev.streetsnap.app.edge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-lifetime aggregate counters for the developer diagnostics panel: how many ranking
 * jobs d1 actually finished, their outcome breakdown, average wall-clock, the first-result
 * latency, the latest per-pass timings, and foreground session lengths. In-memory only — the
 * counters intentionally reset with the process; they are telemetry for this run, not
 * persisted analytics. All timestamps are elapsed-realtime milliseconds supplied by callers,
 * so the whole object stays free of Android imports and unit-testable on the JVM.
 */
internal object InferenceStats {
    /** Outcome bucket for one finished ranking job. Mirrors CompositionOutcome, kept local to avoid the edge layer depending on composition. */
    enum class RankOutcome { ADOPTED, ABSTAINED, TIMED_OUT, FAILED }

    data class Snapshot(
        val rankingsFinished: Int,
        val adopted: Int,
        val abstained: Int,
        val timedOut: Int,
        val failed: Int,
        /** Jobs with a known wall-clock only (failures mid-run may contribute none). */
        val timedRankings: Int,
        val totalRankMs: Long,
        val firstRankMs: Long?,
        val lastVisionMs: Long?,
        val lastEmbeddingsMs: Long?,
        val lastDecisionMs: Long?,
        val lastPromptTokens: Int?,
        val foregroundSessions: Int,
        val totalForegroundMs: Long,
        /** Elapsed inside the still-open foreground session, or null when backgrounded. */
        val currentForegroundMs: Long?,
    ) {
        val averageRankMs: Long? get() = if (timedRankings > 0) totalRankMs / timedRankings else null
        val averageForegroundMs: Long? get() =
            if (foregroundSessions > 0) totalForegroundMs / foregroundSessions else null
        val adoptionRate: Float? get() =
            if (rankingsFinished > 0) adopted.toFloat() / rankingsFinished else null
    }

    private val lock = Any()
    private val _snapshots = MutableStateFlow(
        Snapshot(0, 0, 0, 0, 0, 0, 0L, null, null, null, null, null, 0, 0L, null),
    )
    val snapshots: StateFlow<Snapshot> = _snapshots.asStateFlow()

    private var rankingsFinished = 0
    private var adopted = 0
    private var abstained = 0
    private var timedOut = 0
    private var failed = 0
    private var timedRankings = 0
    private var totalRankMs = 0L
    private var firstRankMs: Long? = null
    private var lastVisionMs: Long? = null
    private var lastEmbeddingsMs: Long? = null
    private var lastDecisionMs: Long? = null
    private var lastPromptTokens: Int? = null

    private var foregroundSessions = 0
    private var totalForegroundMs = 0L
    private var foregroundStartedAtMs: Long? = null

    /**
     * Records one finished ranking job. [elapsedMs] is the two-pass (first + consistency)
     * wall-clock; pass null when the job failed before any duration was known. The per-pass
     * breakdown comes from the FIRST pass, whose vision tower actually ran — the validation
     * pass only repeats embeddings+decision.
     */
    fun recordRanking(
        outcome: RankOutcome,
        elapsedMs: Long?,
        visionMs: Long?,
        embeddingsMs: Long?,
        decisionMs: Long?,
        promptTokens: Int?,
        nowMs: Long,
    ) = synchronized(lock) {
        rankingsFinished++
        when (outcome) {
            RankOutcome.ADOPTED -> adopted++
            RankOutcome.ABSTAINED -> abstained++
            RankOutcome.TIMED_OUT -> timedOut++
            RankOutcome.FAILED -> failed++
        }
        if (elapsedMs != null && elapsedMs >= 0L) {
            timedRankings++
            totalRankMs += elapsedMs
            if (firstRankMs == null) firstRankMs = elapsedMs
        }
        // null and -1 both mean "not measured this pass" (timeout/failure/unknown ranker):
        // keep the latest real values rather than blanking the panel.
        visionMs?.takeIf { it >= 0L }?.let { lastVisionMs = it }
        embeddingsMs?.takeIf { it >= 0L }?.let { lastEmbeddingsMs = it }
        decisionMs?.takeIf { it >= 0L }?.let { lastDecisionMs = it }
        promptTokens?.takeIf { it >= 0 }?.let { lastPromptTokens = it }
        publish(nowMs)
    }

    /** Length of the still-open foreground session at [nowMs], or null when backgrounded. */
    fun currentForegroundDurationMs(nowMs: Long): Long? = synchronized(lock) {
        foregroundStartedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
    }

    /** The object is process-wide, so JVM tests need a clean slate between cases. */
    internal fun resetForTests() = synchronized(lock) {
        rankingsFinished = 0
        adopted = 0
        abstained = 0
        timedOut = 0
        failed = 0
        timedRankings = 0
        totalRankMs = 0L
        firstRankMs = null
        lastVisionMs = null
        lastEmbeddingsMs = null
        lastDecisionMs = null
        lastPromptTokens = null
        foregroundSessions = 0
        totalForegroundMs = 0L
        foregroundStartedAtMs = null
        _snapshots.value = Snapshot(0, 0, 0, 0, 0, 0, 0L, null, null, null, null, null, 0, 0L, null)
    }

    /** Tracks one ON_RESUME/ON_PAUSE app session; duplicate edges are ignored. */
    fun onForeground(active: Boolean, nowMs: Long) = synchronized(lock) {
        if (active) {
            if (foregroundStartedAtMs == null) foregroundStartedAtMs = nowMs
        } else {
            foregroundStartedAtMs?.let { startedAt ->
                foregroundSessions++
                totalForegroundMs += (nowMs - startedAt).coerceAtLeast(0L)
                foregroundStartedAtMs = null
            }
        }
        publish(nowMs)
    }

    private fun publish(nowMs: Long) {
        _snapshots.value = Snapshot(
            rankingsFinished, adopted, abstained, timedOut, failed,
            timedRankings, totalRankMs, firstRankMs,
            lastVisionMs, lastEmbeddingsMs, lastDecisionMs, lastPromptTokens,
            foregroundSessions, totalForegroundMs,
            foregroundStartedAtMs?.let { nowMs - it },
        )
    }
}
