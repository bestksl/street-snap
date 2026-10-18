package dev.streetsnap.app.composition

/**
 * Per-foreground-session nudge budget shared by look and exposure suggestions
 * (docs/look-recommendation-research.md §2.1 rule 4): at most [maxLook] look
 * suggestions and [maxTotal] suggestions of either kind. Reset on foreground entry.
 */
internal class AdviceSessionBudget(
    private val maxLook: Int = MAX_LOOK,
    private val maxTotal: Int = MAX_TOTAL,
) {
    private var lookUsed = 0
    private var totalUsed = 0

    fun canAcquire(isLook: Boolean): Boolean =
        totalUsed < maxTotal && (!isLook || lookUsed < maxLook)

    fun acquire(isLook: Boolean) {
        totalUsed++
        if (isLook) lookUsed++
    }

    fun reset() {
        lookUsed = 0
        totalUsed = 0
    }

    companion object {
        const val MAX_LOOK = 2
        const val MAX_TOTAL = 3
    }
}
