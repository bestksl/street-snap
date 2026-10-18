package dev.streetsnap.camera

/** A rectangle in the uncropped camera frame, with all coordinates normalized to 0..1. */
data class NormalizedBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }

    fun interpolate(target: NormalizedBoundingBox, amount: Float): NormalizedBoundingBox {
        val t = amount.coerceIn(0f, 1f)
        return NormalizedBoundingBox(
            left + (target.left - left) * t,
            top + (target.top - top) * t,
            right + (target.right - right) * t,
            bottom + (target.bottom - bottom) * t,
        )
    }
}

/** Output from a high-frequency subject detector such as YOLO-Nano. */
data class SubjectDetection(
    val box: NormalizedBoundingBox,
    val confidence: Float,
    val label: String,
    val frameTimestampNs: Long,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
        require(label.length <= 80)
        require(frameTimestampNs >= 0L)
    }
}

/** Output from a low-frequency composition advisor. */
data class CompositionAdvice(
    val score: Float,
    val problemKey: String?,
    val recommendBox: NormalizedBoundingBox,
    val frameTimestampNs: Long,
    val modelId: String,
) {
    init {
        require(score.isFinite() && score in 0f..1f)
        require(problemKey == null || problemKey.length <= 64)
        require(modelId.length in 1..80)
        require(frameTimestampNs >= 0L)
    }
}

/** A model adapter consumes a frame but never owns or closes it. */
fun interface SubjectDetector {
    suspend fun detect(frame: CompositionFrame): List<SubjectDetection>
}

/** A low-frequency model adapter; implementations must return null on an unusable answer. */
fun interface CompositionAdvisor {
    suspend fun advise(frame: CompositionFrame): CompositionAdvice?
}

/**
 * Small, deterministic box filter for rendering asynchronous detector results smoothly.
 * A long gap starts a new track so an old subject cannot drag a fresh scene into place.
 */
class BoundingBoxSmoother(
    private val amount: Float = 0.35f,
    private val resetAfterMs: Long = 750L,
) {
    init {
        require(amount in 0f..1f)
        require(resetAfterMs > 0L)
    }

    private var previous: NormalizedBoundingBox? = null
    private var previousAtMs: Long? = null

    fun update(box: NormalizedBoundingBox, timestampMs: Long): NormalizedBoundingBox {
        require(timestampMs >= 0L)
        val old = previous
        val oldAt = previousAtMs
        val smoothed = if (old == null || oldAt == null || timestampMs < oldAt ||
            timestampMs - oldAt > resetAfterMs
        ) box else old.interpolate(box, amount)
        previous = smoothed
        previousAtMs = timestampMs
        return smoothed
    }

    fun clear() {
        previous = null
        previousAtMs = null
    }
}

/** Picks the most confident usable subject for a single follow box. */
fun selectPrimarySubject(detections: List<SubjectDetection>, minimumConfidence: Float = 0.35f): SubjectDetection? {
    require(minimumConfidence in 0f..1f)
    return detections.asSequence()
        .filter { it.confidence >= minimumConfidence }
        .maxByOrNull { it.confidence }
}
