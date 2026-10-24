package dev.streetsnap.app.pose

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * T72 pose assistant — pure-Kotlin geometry layer (zero Android dependencies so the
 * whole coaching logic is covered by JVM unit tests).
 *
 * Coordinate convention everywhere in this package is the **image convention**:
 * x grows right, y grows DOWN, positions are normalised 0..1 for observed samples.
 * MediaPipe's "left" joints are the photographed person's left; with the rear camera
 * they appear on the right side of the picture (larger x), which the templates share.
 */
internal object PoseJoint {
    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    /** The subset the stick figure draws and the v1 catalogue measures. */
    val SKELETON_EDGES = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
        LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP,
        LEFT_HIP to RIGHT_HIP,
        LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE,
    )
}

internal data class PosePoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    /** MediaPipe presence/visibility in 0..1; synthesised templates pass 1. */
    val visibility: Float = 1f,
) {
    val isVisible: Boolean get() = visibility >= VISIBILITY_THRESHOLD

    operator fun minus(other: PosePoint) = PosePoint(x - other.x, y - other.y, z - other.z)
    operator fun plus(other: PosePoint) = PosePoint(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Float) = PosePoint(x * scale, y * scale, z * scale)

    companion object {
        const val VISIBILITY_THRESHOLD = 0.5f
    }
}

/** One observed person. [points] is indexed by the MediaPipe 33-joint order. */
internal data class PoseSample(
    val points: List<PosePoint>,
    val timestampMs: Long,
) {
    fun point(index: Int): PosePoint? = points.getOrNull(index)?.takeIf { it.isVisible }
}

internal fun dist(first: PosePoint, second: PosePoint): Float =
    hypot(first.x - second.x, first.y - second.y)

/** Interior angle at [vertex] in degrees, 0..180. */
internal fun angle(previous: PosePoint, vertex: PosePoint, next: PosePoint): Float {
    val v1x = previous.x - vertex.x
    val v1y = previous.y - vertex.y
    val v2x = next.x - vertex.x
    val v2y = next.y - vertex.y
    val dot = v1x * v2x + v1y * v2y
    val cross = v1x * v2y - v1y * v2x
    return Math.toDegrees(atan2(kotlin.math.abs(cross), dot).toDouble()).toFloat()
}

internal fun midpoint(first: PosePoint, second: PosePoint) =
    PosePoint((first.x + second.x) / 2f, (first.y + second.y) / 2f)

/** Linear 0..1 ramp: full at [full], zero at [zero] (works whether full > zero or not). */
internal fun ramp(value: Float, zero: Float, full: Float): Float = when {
    full >= zero -> ((value - zero) / (full - zero)).coerceIn(0f, 1f)
    else -> ((zero - value) / (zero - full)).coerceIn(0f, 1f)
}

/**
 * A target pose as normalised template joints (image convention, upright person).
 * Template space is arbitrary (the torso is ~0.22 units tall); [projectTo] rescales and
 * rotates it onto an observed body, and [projectCentered] maps it into a fixed demo box.
 */
internal data class PoseTemplate(val joints: Map<Int, PosePoint>) {
    fun joint(index: Int): PosePoint = requireNotNull(joints[index]) { "template missing joint $index" }

    val shoulderMid: PosePoint get() = midpoint(joint(PoseJoint.LEFT_SHOULDER), joint(PoseJoint.RIGHT_SHOULDER))
    val hipMid: PosePoint get() = midpoint(joint(PoseJoint.LEFT_HIP), joint(PoseJoint.RIGHT_HIP))
    val torsoLength: Float get() = dist(shoulderMid, hipMid)

    /**
     * Align the template onto the observed body: same torso centre, size and lean. Joints
     * the observed sample cannot see are still projected (the amber target needs them),
     * so projection never depends on per-joint visibility.
     */
    fun projectTo(sample: PoseSample): Map<Int, PosePoint>? {
        val ls = sample.point(PoseJoint.LEFT_SHOULDER) ?: return null
        val rs = sample.point(PoseJoint.RIGHT_SHOULDER) ?: return null
        val lh = sample.point(PoseJoint.LEFT_HIP) ?: return null
        val rh = sample.point(PoseJoint.RIGHT_HIP) ?: return null
        val observedShoulderMid = midpoint(ls, rs)
        val observedHipMid = midpoint(lh, rh)
        val observedTorso = dist(observedShoulderMid, observedHipMid).takeIf { it > 1e-4f } ?: return null
        val templateMid = midpoint(shoulderMid, hipMid)
        val observedMid = midpoint(observedShoulderMid, observedHipMid)
        val scale = observedTorso / torsoLength
        // Image convention: angle of the shoulder->hip vector. Template torso is vertical.
        val theta = atan2(
            observedHipMid.y - observedShoulderMid.y,
            observedHipMid.x - observedShoulderMid.x,
        ) - Math.PI / 2.0
        val cos = cos(theta).toFloat()
        val sin = sin(theta).toFloat()
        return joints.mapValues { (_, point) ->
            val relative = point - templateMid
            val rotated = PosePoint(relative.x * cos - relative.y * sin, relative.x * sin + relative.y * cos)
            observedMid + rotated * scale
        }
    }

    companion object {
        /**
         * Demo-mode mapping: the template's standing figure is centred in a box of
         * [extent] pixels, [pixelsPerUnit] scaling its own coordinate space.
         */
        fun projectCentered(
            joints: Map<Int, PosePoint>,
            centerX: Float,
            topY: Float,
            pixelsPerUnit: Float,
        ): Map<Int, PosePoint> {
            val xs = joints.values.map { it.x }
            val minY = joints.values.minOf { it.y }
            val minX = xs.minOrNull() ?: 0f
            val maxX = xs.maxOrNull() ?: 0f
            val offsetX = (minX + maxX) / 2f
            return joints.mapValues { (_, point) ->
                PosePoint(centerX + (point.x - offsetX) * pixelsPerUnit,
                    topY + (point.y - minY) * pixelsPerUnit)
            }
        }
    }
}
