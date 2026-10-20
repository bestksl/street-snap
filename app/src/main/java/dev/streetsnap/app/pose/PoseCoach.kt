package dev.streetsnap.app.pose

/**
 * Scores observed samples against the selected [PoseDefinition] and holds the
 * "pose achieved" state. Pure Kotlin: every frame in, one [PoseGuide] out.
 *
 * Unlike the composition alignment tracker, striking the pose immediately when the
 * feature opens is a success (it is a teaching tool the user opted into), so there is no
 * "must first see a need" arming step. Hysteresis bands keep the ready state from
 * flickering on a single jittery frame.
 */
internal data class PoseGuide(
    val definitionId: String,
    val personVisible: Boolean,
    val score: Float,
    val ready: Boolean,
    val hint: String?,
    val sample: PoseSample?,
    /** Elapsed-realtime timestamp of the one-shot achieved edge, 0 before the first. */
    val achievedAtMs: Long,
)

internal class PoseCoach(
    private val holdFrames: Int = HOLD_FRAMES,
    private val rearmFrames: Int = REARM_FRAMES,
) {
    private var definition: PoseDefinition = PoseCatalog.poses.first()
    private var ready = false
    private var goodStreak = 0
    private var lostStreak = 0
    private var lastSample: PoseSample? = null

    fun current(): PoseDefinition = definition

    fun select(definition: PoseDefinition) {
        if (definition.id == this.definition.id) return
        this.definition = definition
        reset()
    }

    /** Clears hysteresis state without changing the selected pose. */
    fun reset() {
        ready = false
        goodStreak = 0
        lostStreak = 0
    }

    fun next(): PoseDefinition {
        val index = PoseCatalog.poses.indexOfFirst { it.id == definition.id }
        return PoseCatalog.poses[(index + 1) % PoseCatalog.poses.size].also(::select)
    }

    /** Feed null (or a sample whose torso is invisible) when nobody is in frame. */
    fun observe(sample: PoseSample?, nowMs: Long): PoseGuide {
        lastSample = sample
        if (sample == null || !PoseCatalog.personVisible(sample)) {
            goodStreak = 0
            lostStreak = 0
            ready = false
            return PoseGuide(definition.id, personVisible = false, score = 0f, ready = false,
                hint = if (sample == null) null else "看不到完整身体，往后退一点，让头和脚都入镜",
                sample = sample, achievedAtMs = 0L)
        }
        val outcomes = definition.evaluate(sample)
        val score = outcomes.map { it.score }.average().toFloat().coerceIn(0f, 1f)
        var achievedAtMs = 0L
        if (ready) {
            if (score >= SCORE_DROP) {
                lostStreak = 0
            } else if (++lostStreak >= rearmFrames) {
                ready = false
                goodStreak = 0
            }
        } else if (score >= SCORE_READY) {
            if (++goodStreak >= holdFrames) {
                ready = true
                lostStreak = 0
                achievedAtMs = nowMs
            }
        } else {
            goodStreak = 0
        }
        // Missing limbs first (stepping into frame is the prerequisite), then the weakest
        // measured condition; a satisfied condition carries no hint.
        val hint = outcomes
            .filter { !it.visible }
            .minByOrNull { it.score }
            ?.hint
            ?: outcomes.filter { it.hint != null }.minByOrNull { it.score }?.hint
        return PoseGuide(definition.id, personVisible = true, score = score, ready = ready,
            hint = if (ready) null else hint, sample = sample, achievedAtMs = achievedAtMs)
    }

    companion object {
        const val SCORE_READY = 0.75f
        const val SCORE_DROP = 0.60f
        // ~0.5 s at the ~8 fps pose cadence; jitter on one frame never lights the cue.
        const val HOLD_FRAMES = 4
        const val REARM_FRAMES = 3
    }
}
