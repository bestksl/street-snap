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
        // Raised 2->3 / 3->5 on 2026-10-19 when look suggestions moved from an in-viewfinder
        // card to a quiet nameplate chip and users reported the old cap made them too rare.
        const val MAX_LOOK = 3
        const val MAX_TOTAL = 5
    }
}
