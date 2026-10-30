package dev.streetsnap.app.composition

/**
 * Runs one composition request at a time, with a rest measured from its completion.
 * A slow device gets time to finish its first frame and a longer rest before the next
 * one. The caller owns serialization and cancellation; these budgets never queue frames.
 */
internal class CompositionCadence(
    private val minimumCooldownMs: Long = 3_000L,
    private val maximumCooldownMs: Long = 15_000L,
    private val initialBudgetMs: Long = 30_000L,
    private val maximumBudgetMs: Long = 60_000L,
) {
    enum class Outcome { COMPLETED_WITH_RESULT, COMPLETED_NO_RESULT, TIMED_OUT }

    data class Completion(
        val outcome: Outcome,
        val elapsedMs: Long,
        val nextBudgetMs: Long,
        val cooldownMs: Long,
    )

    init {
        require(minimumCooldownMs > 0L && maximumCooldownMs >= minimumCooldownMs)
        require(initialBudgetMs > 0L && maximumBudgetMs >= initialBudgetMs)
    }

    private var currentBudgetMs = initialBudgetMs
    private var currentCooldownMs = minimumCooldownMs

    @Synchronized fun budgetMs(): Long = currentBudgetMs

    /** Wait this long after a request ends before sampling another fresh frame. */
    @Synchronized fun cooldownMs(): Long = currentCooldownMs

    /**
     * A completed request is never classified as a timeout by its elapsed time. In
     * particular, a null/invalid answer is a completed inference, not cancellation.
     */
    @Synchronized fun recordCompleted(elapsedMs: Long, hasUsableResult: Boolean): Completion {
        require(elapsedMs >= 0L)
        // Add 50% headroom plus two seconds without overflowing for unusual timings.
        val boundedElapsedMs = elapsedMs.coerceAtMost(maximumBudgetMs)
        val headroomMs = (boundedElapsedMs / 2L).coerceAtMost(maximumBudgetMs - boundedElapsedMs)
        val withHeadroomMs = boundedElapsedMs + headroomMs
        val marginMs = 2_000L.coerceAtMost(maximumBudgetMs - withHeadroomMs)
        currentBudgetMs = (withHeadroomMs + marginMs).coerceIn(initialBudgetMs, maximumBudgetMs)
        currentCooldownMs = (elapsedMs / 2L).coerceIn(minimumCooldownMs, maximumCooldownMs)
        return completion(
            if (hasUsableResult) Outcome.COMPLETED_WITH_RESULT else Outcome.COMPLETED_NO_RESULT,
            elapsedMs,
        )
    }

    /** Only call when cancellation actually reached the request's time limit. */
    @Synchronized fun recordTimeout(elapsedMs: Long): Completion {
        require(elapsedMs >= 0L)
        currentBudgetMs += currentBudgetMs.coerceAtMost(maximumBudgetMs - currentBudgetMs)
        currentCooldownMs = maximumCooldownMs
        return completion(Outcome.TIMED_OUT, elapsedMs)
    }

    @Synchronized fun reset() {
        currentBudgetMs = initialBudgetMs
        currentCooldownMs = minimumCooldownMs
    }

    private fun completion(outcome: Outcome, elapsedMs: Long) = Completion(
        outcome = outcome,
        elapsedMs = elapsedMs,
        nextBudgetMs = currentBudgetMs,
        cooldownMs = currentCooldownMs,
    )
}
