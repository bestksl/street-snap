package dev.streetsnap.app.composition

/**
 * Remembers the moment of an explicit tap-to-focus request. For a short while afterwards the
 * AF motor hunt and the AE/AWB re-convergence momentarily change the picture (global blur,
 * brightness shift); the composition layer's scene signature would otherwise read that as
 * "scene changed" and immediately clear the advice rectangle and its movement arrow — the
 * "I tap to focus and the arrow vanishes" bug (T76). Scene-change clears are suppressed while
 * [isDisturbing] is true; a genuine pan after the AF settles is detected normally.
 *
 * Pure Kotlin with an injected clock so JVM unit tests can drive time directly.
 */
internal class FocusDisturbanceGate(
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val now: () -> Long = { 0L },
) {
    private var engagedAtMs = Long.MIN_VALUE

    fun engage() {
        engagedAtMs = now()
    }

    /** True from the tap until [graceMs] later; stale/never-engaged gates report false. */
    fun isDisturbing(atMs: Long = now()): Boolean {
        if (engagedAtMs == Long.MIN_VALUE) return false
        val delta = atMs - engagedAtMs
        return delta >= 0 && delta < graceMs
    }

    fun reset() {
        engagedAtMs = Long.MIN_VALUE
    }

    companion object {
        // AF typically converges within ~0.3–0.8 s; the margin also covers the finger jitter
        // from tapping the screen. Kept short so a real re-frame is never hidden for long.
        const val DEFAULT_GRACE_MS = 1500L
    }
}
