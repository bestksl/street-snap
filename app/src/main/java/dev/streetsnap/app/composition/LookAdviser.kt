package dev.streetsnap.app.composition

/** A visible look recommendation; the card state (suggesting vs trying) lives in the ViewModel. */
internal data class LookAdvice(val key: String, val target: LookTarget)

/**
 * Anti-nag state machine for realtime look suggestions
 * (see docs/look-recommendation-research.md §2.1):
 *
 * - the same mapping must win [stableFrames] consecutive frames before it is shown;
 * - one look key is suggested at most once per foreground session, within [sessionBudget];
 * - a key inside [suppressMillis] of a dismiss is suppressed;
 * - once the user picks a look by hand the adviser falls silent until the next foreground
 *   session (Fujifilm rule: leaving AUTO for a manual film simulation ends AUTO);
 * - a scene change aborts the pending streak and hides the card, but the per-session
 *   history survives, so the same suggestion cannot come straight back.
 *
 * Pure Kotlin, clock injected for tests; no Android types, no frame scheduling of its own.
 */
internal class LookAdviser(
    private val stableFrames: Int = DEFAULT_STABLE_FRAMES,
    private val suppressMillis: Long = SUPPRESS_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val budget: AdviceSessionBudget = AdviceSessionBudget(),
) {
    private var candidateKey: String? = null
    private var candidateCount = 0
    private val shownKeys = mutableSetOf<String>()
    private var silenced = false
    private var visibleKey: String? = null

    /** New foreground session: budgets and the manual-pick silence reset. */
    fun resetSession() {
        candidateKey = null
        candidateCount = 0
        shownKeys.clear()
        silenced = false
        visibleKey = null
        budget.reset()
    }

    /** The user chose a look themselves (panel, badge tap); do not advise until resetSession(). */
    fun onUserPickedLook() {
        silenced = true
        candidateKey = null
        candidateCount = 0
        visibleKey = null
    }

    /** Scene signature changed: drop the pending streak; shown history stays for this session. */
    fun onSceneChanged() {
        candidateKey = null
        candidateCount = 0
        visibleKey = null
    }

    /**
     * Feed one scene reading. Returns the advice to show exactly once, or null.
     *
     * @param activeLookKey the user's current look identity — never suggest what is already on
     * @param dismissedAt persistent key → dismiss timestamp map (7-day memory lives in settings)
     */
    fun onFrame(reading: SceneReading?, activeLookKey: String, dismissedAt: Map<String, Long>): LookAdvice? {
        if (silenced || reading == null) return null
        val target = LookRecommendations.from(reading)
        if (target == null || target.key == activeLookKey) {
            resetStreak()
            return null
        }
        val suppressed = dismissedAt[target.key]?.let { ts -> now() - ts in 0..suppressMillis } == true
        if (suppressed || target.key in shownKeys || !budget.canAcquire(isLook = true)) {
            resetStreak()
            return null
        }
        if (candidateKey != target.key) {
            candidateKey = target.key
            candidateCount = 1
        } else {
            candidateCount++
        }
        if (candidateCount < stableFrames || visibleKey == target.key) return null
        visibleKey = target.key
        shownKeys += target.key
        budget.acquire(isLook = true)
        return LookAdvice(target.key, target)
    }

    /** A shown advice was tried or dismissed by the user; it must not reappear this session. */
    fun markConsumed(key: String) {
        shownKeys += key
        if (visibleKey == key) visibleKey = null
    }

    private fun resetStreak() {
        candidateKey = null
        candidateCount = 0
    }

    companion object {
        /** Analysis frames arrive on a ~250 ms clock, so 8 frames ≈ 2 s of stable scene. */
        const val DEFAULT_STABLE_FRAMES = 8
        const val SUPPRESS_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
