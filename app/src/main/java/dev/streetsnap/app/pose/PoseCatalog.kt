package dev.streetsnap.app.pose

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The v1 pose library (T72): eight common standing portrait poses. Every condition is a
 * continuous 0..1 satisfaction score plus a joint-level hint for its unmet side, so the
 * coach can say which limb to move instead of only "not yet".
 *
 * Thresholds are a conservative v1 calibration in torso-length units (distance between
 * shoulder-mid and hip-mid = 1 T). They are documented inline and must be re-tuned on
 * real photographed subjects — the evidence level is experimental, not verified.
 */
internal data class PoseOutcome(
    val label: String,
    val score: Float,
    /** Null when the condition is satisfied; otherwise the joint-level nudge. */
    val hint: String?,
    /** False when a required joint is not visible: the hint then asks the person to step in. */
    val visible: Boolean = true,
)

internal class PoseDefinition(
    val id: String,
    val title: String,
    val invite: String,
    val template: PoseTemplate,
    val evaluate: (PoseSample) -> List<PoseOutcome>,
)

internal object PoseCatalog {
    // Lazy: the individual pose vals are declared below; object properties initialise in
    // declaration order, so an eager list here would see them uninitialized.
    val poses: List<PoseDefinition> by lazy {
        listOf(
            oneArmUp, bothArmsUp, handOnHead, handsOnHips,
            twist, tiltHead, lookBack, weightBack,
        )
    }

    fun byId(id: String): PoseDefinition = poses.first { it.id == id }

    // Shoulders/hips visible is the minimum for any coaching or target projection.
    fun personVisible(sample: PoseSample): Boolean =
        sample.point(PoseJoint.LEFT_SHOULDER) != null &&
            sample.point(PoseJoint.RIGHT_SHOULDER) != null &&
            sample.point(PoseJoint.LEFT_HIP) != null &&
            sample.point(PoseJoint.RIGHT_HIP) != null

    private val SIDE_LEFT = "左"
    private val SIDE_RIGHT = "右"

    private class Eval(private val sample: PoseSample) {
        fun point(index: Int): PosePoint? = sample.point(index)

        fun require(index: Int, part: String, block: (PosePoint) -> PoseOutcome): PoseOutcome =
            point(index)?.let(block)
                ?: PoseOutcome(part, 0f, "看不到你的$part，往画面中间站一点", visible = false)

        fun torso(): Triple<PosePoint, PosePoint, Float>? {
            val ls = point(PoseJoint.LEFT_SHOULDER) ?: return null
            val rs = point(PoseJoint.RIGHT_SHOULDER) ?: return null
            val lh = point(PoseJoint.LEFT_HIP) ?: return null
            val rh = point(PoseJoint.RIGHT_HIP) ?: return null
            val length = dist(midpoint(ls, rs), midpoint(lh, rh)).takeIf { it > 1e-4f } ?: return null
            return Triple(midpoint(ls, rs), midpoint(lh, rh), length)
        }
    }

    // ---- side geometry -------------------------------------------------------

    private data class ArmGeometry(
        val side: String,
        val shoulder: PosePoint,
        val elbow: PosePoint,
        val wrist: PosePoint,
        val lift: Float,
        val elbowAngle: Float,
    )

    private fun arm(sample: PoseSample, side: String, shoulderI: Int, elbowI: Int, wristI: Int,
        torsoLength: Float): ArmGeometry? {
        val shoulder = sample.point(shoulderI) ?: return null
        val elbow = sample.point(elbowI) ?: return null
        val wrist = sample.point(wristI) ?: return null
        return ArmGeometry(side, shoulder, elbow, wrist,
            lift = (shoulder.y - wrist.y) / torsoLength,
            elbowAngle = angle(shoulder, elbow, wrist))
    }

    private fun armOutcome(metric: String, score: Float, unmet: String, visible: Boolean = true) =
        PoseOutcome(metric, score.coerceIn(0f, 1f), if (score >= SIDE_CONDITION_FULL) null else unmet, visible)

    // A condition is treated as "met" (no hint) from this score up; the pose-level gate is stricter.
    private const val SIDE_CONDITION_FULL = 0.92f

    // ---- 1. one arm up -------------------------------------------------------

    private val oneArmUp = PoseDefinition(
        id = "one_arm_up",
        title = "单手举高",
        invite = "一只手向上举，手腕高过头顶，手肘伸直",
        template = PoseTemplate(neutralJoints(
            rightElbow = PosePoint(-.13f, .16f), rightWrist = PosePoint(-.10f, .02f),
        )),
    ) { sample ->
        val eval = Eval(sample)
        val torso = eval.torso() ?: return@PoseDefinition listOf(missingBody())
        val (_, _, t) = torso
        val arms = listOfNotNull(
            arm(sample, SIDE_LEFT, PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_ELBOW, PoseJoint.LEFT_WRIST, t),
            arm(sample, SIDE_RIGHT, PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_ELBOW, PoseJoint.RIGHT_WRIST, t),
        )
        val chosen = arms.maxByOrNull { minOf(ramp(it.lift, -.10f, .35f), ramp(it.elbowAngle, 120f, 165f)) }
            ?: return@PoseDefinition listOf(missingArms())
        // Wrist over the top of the head (~0.35 T above the shoulder line), arm nearly straight.
        val liftScore = ramp(chosen.lift, -.10f, .35f)
        val straightScore = ramp(chosen.elbowAngle, 120f, 165f)
        listOf(
            armOutcome("手腕高过头顶", liftScore, "${chosen.side}手再往上举，高过头顶",
                visible = chosen.wrist.visibility >= PosePoint.VISIBILITY_THRESHOLD),
            armOutcome("手臂伸直", straightScore, "${chosen.side}手肘再伸直一点"),
        )
    }

    // ---- 2. both arms up -----------------------------------------------------

    private val bothArmsUp = PoseDefinition(
        id = "both_arms_up",
        title = "双手举起",
        invite = "双手向上举高，两个手肘都伸直",
        template = PoseTemplate(neutralJoints(
            leftElbow = PosePoint(.11f, .15f), leftWrist = PosePoint(.085f, 0f),
            rightElbow = PosePoint(-.11f, .15f), rightWrist = PosePoint(-.085f, 0f),
        )),
    ) { sample ->
        val eval = Eval(sample)
        val torso = eval.torso() ?: return@PoseDefinition listOf(missingBody())
        val (_, _, t) = torso
        fun armSide(side: String, s: Int, e: Int, w: Int): ArmGeometry? = arm(sample, side, s, e, w, t)
        val left = armSide(SIDE_LEFT, PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_ELBOW, PoseJoint.LEFT_WRIST)
        val right = armSide(SIDE_RIGHT, PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_ELBOW, PoseJoint.RIGHT_WRIST)
        if (left == null || right == null) return@PoseDefinition listOf(missingArms())
        val heightScore = minOf(ramp(left.lift, -.10f, .30f), ramp(right.lift, -.10f, .30f))
        val straightScore = minOf(ramp(left.elbowAngle, 110f, 155f), ramp(right.elbowAngle, 110f, 155f))
        val worst = if (left.lift < right.lift) SIDE_LEFT else SIDE_RIGHT
        listOf(
            PoseOutcome("双腕高于肩", heightScore,
                if (heightScore < SIDE_CONDITION_FULL) "$worst 手再抬高一些，举过肩膀" else null),
            PoseOutcome("双手伸直", straightScore,
                if (straightScore < SIDE_CONDITION_FULL) "手肘不要弯，再伸直一点" else null),
        )
    }

    // ---- 3. one hand on the head --------------------------------------------

    private val handOnHead = PoseDefinition(
        id = "hand_on_head",
        title = "手放头上",
        invite = "一只手抬到头侧，像撩头发那样，手肘向外打开",
        template = PoseTemplate(neutralJoints(
            rightElbow = PosePoint(-.20f, .16f), rightWrist = PosePoint(-.04f, .06f),
        )),
    ) { sample ->
        val eval = Eval(sample)
        val torso = eval.torso() ?: return@PoseDefinition listOf(missingBody())
        val (shoulderMid, _, t) = torso
        val nose = sample.point(PoseJoint.NOSE)
            ?: return@PoseDefinition listOf(PoseOutcome("手靠近头部", 0f, "看不到头，往画面中间站一点", visible = false))
        // Face length (nose -> shoulder-mid) is the always-available head-size proxy.
        val faceLen = dist(nose, shoulderMid).takeIf { it > 1e-4f } ?: t
        val shoulderHalf = abs(shoulderMid.x - (sample.point(PoseJoint.RIGHT_SHOULDER)?.x ?: shoulderMid.x))
            .takeIf { it > 1e-4f } ?: t
        data class Candidate(val side: String, val elbow: PosePoint, val wrist: PosePoint)
        val candidates = listOfNotNull(
            sample.point(PoseJoint.LEFT_ELBOW)?.let { e -> sample.point(PoseJoint.LEFT_WRIST)?.let { w -> Candidate(SIDE_LEFT, e, w) } },
            sample.point(PoseJoint.RIGHT_ELBOW)?.let { e -> sample.point(PoseJoint.RIGHT_WRIST)?.let { w -> Candidate(SIDE_RIGHT, e, w) } },
        )
        if (candidates.isEmpty()) return@PoseDefinition listOf(missingArms())
        val chosen = candidates.maxByOrNull { c ->
            minOf(ramp(dist(c.wrist, nose) / faceLen, 1.5f, .55f),
                ramp(abs(c.elbow.x - shoulderMid.x) / shoulderHalf, .9f, 1.6f))
        }!!
        val nearScore = ramp(dist(chosen.wrist, nose) / faceLen, 1.5f, .55f)
        val elbowScore = ramp(abs(chosen.elbow.x - shoulderMid.x) / shoulderHalf, .9f, 1.6f)
        listOf(
            PoseOutcome("手靠近头部", nearScore,
                if (nearScore < SIDE_CONDITION_FULL) "${chosen.side}手抬到头侧，像撩头发那样" else null),
            PoseOutcome("手肘外展", elbowScore,
                if (elbowScore < SIDE_CONDITION_FULL) "${chosen.side}肘向外打开" else null),
        )
    }

    // ---- 4. hands on hips ----------------------------------------------------

    private val handsOnHips = PoseDefinition(
        id = "hands_on_hips",
        title = "双手叉腰",
        invite = "双手手腕贴到腰侧，双肘向外打开",
        template = PoseTemplate(neutralJoints(
            leftElbow = PosePoint(.22f, .40f), leftWrist = PosePoint(.11f, .49f),
            rightElbow = PosePoint(-.22f, .40f), rightWrist = PosePoint(-.11f, .49f),
        )),
    ) { sample ->
        val eval = Eval(sample)
        val torso = eval.torso() ?: return@PoseDefinition listOf(missingBody())
        val (shoulderMid, hipMid, t) = torso
        val shoulderHalf = abs(shoulderMid.x - (sample.point(PoseJoint.LEFT_SHOULDER)?.x ?: shoulderMid.x))
            .takeIf { it > 1e-4f } ?: t
        data class Side(val side: String, val elbow: PosePoint, val wrist: PosePoint)
        val sides = listOfNotNull(
            sample.point(PoseJoint.LEFT_ELBOW)?.let { e -> sample.point(PoseJoint.LEFT_WRIST)?.let { w -> Side(SIDE_LEFT, e, w) } },
            sample.point(PoseJoint.RIGHT_ELBOW)?.let { e -> sample.point(PoseJoint.RIGHT_WRIST)?.let { w -> Side(SIDE_RIGHT, e, w) } },
        )
        if (sides.size < 2) return@PoseDefinition listOf(missingArms())
        val wristScore = ramp(sides.maxOf { dist(it.wrist, hipMid) / t }, 1.0f, .40f)
        val elbowScore = ramp(sides.minOf { abs(it.elbow.x - shoulderMid.x) / shoulderHalf }, 1.0f, 1.6f)
        listOf(
            PoseOutcome("双腕在腰侧", wristScore,
                if (wristScore < SIDE_CONDITION_FULL) "手腕落到腰侧，叉在胯骨上方" else null),
            PoseOutcome("双肘外展", elbowScore,
                if (elbowScore < SIDE_CONDITION_FULL) "双肘再向外打开" else null),
        )
    }

    // ---- 5. shoulder/hip twist ----------------------------------------------

    private val twist: PoseDefinition
    get() {
        // Shoulders and arms rotate ~25° around the shoulder centre; hips stay put.
        val base = neutralJoints()
        val centre = midpoint(base.getValue(PoseJoint.LEFT_SHOULDER), base.getValue(PoseJoint.RIGHT_SHOULDER))
        val rotated = base.toMutableMap()
        val radians = Math.toRadians(25.0)
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        listOf(
            PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER,
            PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW,
            PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST,
        ).forEach { index ->
            val p = base.getValue(index)
            val rx = p.x - centre.x
            val ry = p.y - centre.y
            rotated[index] = PosePoint(centre.x + rx * cos + ry * sin, centre.y - rx * sin + ry * cos)
        }
        return PoseDefinition(
            id = "twist",
            title = "扭腰转肩",
            invite = "肩膀转向一侧，胯保持朝前",
            template = PoseTemplate(rotated),
        ) { sample ->
            val eval = Eval(sample)
            val ls = sample.point(PoseJoint.LEFT_SHOULDER)
            val rs = sample.point(PoseJoint.RIGHT_SHOULDER)
            val lh = sample.point(PoseJoint.LEFT_HIP)
            val rh = sample.point(PoseJoint.RIGHT_HIP)
            if (ls == null || rs == null || lh == null || rh == null) return@PoseDefinition listOf(missingBody())
            val shoulderAngle = atan2Degrees(ls, rs)
            val hipAngle = atan2Degrees(lh, rh)
            var delta = abs(shoulderAngle - hipAngle) % 180f
            if (delta > 90f) delta = 180f - delta
            val twistScore = ramp(delta, 8f, 28f)
            val hipWidth = dist(lh, rh).takeIf { it > 1e-4f }
            val hipTilt = hipWidth?.let { abs(lh.y - rh.y) / it } ?: 1f
            val hipSteadyScore = ramp(hipTilt, .25f, .08f)
            listOf(
                PoseOutcome("肩胯转角", twistScore,
                    if (twistScore < SIDE_CONDITION_FULL) "肩膀再转过去一点，胯保持朝前" else null),
                PoseOutcome("胯部稳住", hipSteadyScore,
                    if (hipSteadyScore < SIDE_CONDITION_FULL) "胯稳住，不要跟着肩膀一起转" else null),
            )
        }
    }

    private fun atan2Degrees(from: PosePoint, to: PosePoint): Float {
        var degrees = Math.toDegrees(kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
        if (degrees < 0f) degrees += 180f
        return degrees
    }

    // ---- 6. head tilt (look up) ---------------------------------------------

    private val tiltHead = PoseDefinition(
        id = "tilt_head",
        title = "仰头",
        invite = "下巴抬起，看向斜上方",
        template = PoseTemplate(neutralJoints(
            nose = PosePoint(0f, .02f),
            leftEar = PosePoint(.05f, .05f), rightEar = PosePoint(-.05f, .05f),
        )),
    ) { sample ->
        // Weak 2-D proxy (experimental, not verified): with the chin up, the nose sits
        // higher relative to the ear line. Side-profile tilts are not observable head-on.
        val nose = sample.point(PoseJoint.NOSE)
        val lEar = sample.point(PoseJoint.LEFT_EAR)
        val rEar = sample.point(PoseJoint.RIGHT_EAR)
        if (nose == null || lEar == null || rEar == null) {
            return@PoseDefinition listOf(PoseOutcome("脸部可见", 0f, "看不到脸，把脸转进画面", visible = false))
        }
        val earMid = midpoint(lEar, rEar)
        val faceWidth = dist(lEar, rEar).takeIf { it > 1e-4f } ?: return@PoseDefinition listOf(missingBody())
        // Chin up lifts the nose toward/above the ear line in image coordinates, so this
        // grows positive; a level face sits around -0.3 and a clear tilt above +0.4.
        val proxy = (earMid.y - nose.y) / faceWidth
        val score = ramp(proxy, -.20f, .45f)
        listOf(
            PoseOutcome("下巴抬起", score,
                if (score < SIDE_CONDITION_FULL) "下巴再抬起来一点，看斜上方" else null),
        )
    }

    // ---- 7. look back over the shoulder -------------------------------------

    private val lookBack = PoseDefinition(
        id = "look_back",
        title = "回头",
        invite = "头转向侧后方，下巴带到肩外",
        template = PoseTemplate(neutralJoints(
            nose = PosePoint(-.09f, .12f),
            leftEar = PosePoint(.08f, .075f), rightEar = PosePoint(-.04f, .075f),
        )),
    ) { sample ->
        val nose = sample.point(PoseJoint.NOSE)
        val lEar = sample.point(PoseJoint.LEFT_EAR)
        val rEar = sample.point(PoseJoint.RIGHT_EAR)
        if (nose == null || lEar == null || rEar == null) {
            return@PoseDefinition listOf(PoseOutcome("脸部可见", 0f, "看不到脸，把脸转进画面", visible = false))
        }
        val earMid = midpoint(lEar, rEar)
        val earDistance = dist(lEar, rEar).takeIf { it > 1e-4f }
            ?: return@PoseDefinition listOf(PoseOutcome("头转向", 0f, "头转向侧后方，下巴带到肩外"))
        val offset = abs(nose.x - earMid.x) / earDistance
        val score = ramp(offset, .15f, .55f)
        listOf(
            PoseOutcome("头转向", score,
                if (score < SIDE_CONDITION_FULL) "头转向侧后方，下巴带到肩外" else null),
        )
    }

    // ---- 8. weight on the back leg ------------------------------------------

    private val weightBack = PoseDefinition(
        id = "weight_back",
        title = "重心后腿",
        invite = "重心压到后腿，前腿伸直、后腿放松微屈",
        template = PoseTemplate(neutralJoints(
            leftShoulder = PosePoint(.12f, .28f), rightShoulder = PosePoint(-.12f, .28f),
            leftHip = PosePoint(.11f, .50f), rightHip = PosePoint(-.06f, .50f),
            leftKnee = PosePoint(.04f, .75f), rightKnee = PosePoint(-.08f, .74f),
            leftAnkle = PosePoint(.16f, .97f), rightAnkle = PosePoint(-.085f, 1.0f),
        )),
    ) { sample ->
        val eval = Eval(sample)
        val torso = eval.torso() ?: return@PoseDefinition listOf(missingBody())
        val (_, hipMid, t) = torso
        val lh = sample.point(PoseJoint.LEFT_HIP)
        val rh = sample.point(PoseJoint.RIGHT_HIP)
        val lk = sample.point(PoseJoint.LEFT_KNEE)
        val rk = sample.point(PoseJoint.RIGHT_KNEE)
        val la = sample.point(PoseJoint.LEFT_ANKLE)
        val ra = sample.point(PoseJoint.RIGHT_ANKLE)
        if (lh == null || rh == null || lk == null || rk == null || la == null || ra == null) {
            return@PoseDefinition listOf(PoseOutcome("双腿入镜", 0f, "看不到腿，往后退一点让全身入镜", visible = false))
        }
        val ankleMid = midpoint(la, ra)
        val shiftScore = ramp(abs(hipMid.x - ankleMid.x) / t, .05f, .18f)
        val leftKneeAngle = angle(lh, lk, la)
        val rightKneeAngle = angle(rh, rk, ra)
        // The straighter leg is the front one; the other must soften.
        val straightScore = ramp(maxOf(leftKneeAngle, rightKneeAngle), 150f, 170f)
        val bentScore = ramp(minOf(leftKneeAngle, rightKneeAngle), 165f, 135f)
        val kneeScore = minOf(straightScore, bentScore)
        listOf(
            PoseOutcome("重心后移", shiftScore,
                if (shiftScore < SIDE_CONDITION_FULL) "重心压到后腿，胯向支撑腿送" else null),
            PoseOutcome("前直后屈", kneeScore,
                if (kneeScore < SIDE_CONDITION_FULL) "前腿再伸直、后腿放松微屈" else null),
        )
    }

    // ---- shared fallbacks ----------------------------------------------------

    private fun missingBody() = PoseOutcome("身体入镜", 0f, "看不到身体，往画面中间站一点", visible = false)
    private fun missingArms() = PoseOutcome("手臂入镜", 0f, "看不到手臂，往画面中间站一点", visible = false)

    // ---- neutral template + per-pose overrides -------------------------------

    private fun neutralJoints(
        nose: PosePoint = PosePoint(0f, .12f),
        leftEar: PosePoint = PosePoint(.065f, .075f),
        rightEar: PosePoint = PosePoint(-.065f, .075f),
        leftShoulder: PosePoint = PosePoint(.13f, .28f),
        rightShoulder: PosePoint = PosePoint(-.13f, .28f),
        leftElbow: PosePoint = PosePoint(.17f, .42f),
        rightElbow: PosePoint = PosePoint(-.17f, .42f),
        leftWrist: PosePoint = PosePoint(.16f, .56f),
        rightWrist: PosePoint = PosePoint(-.16f, .56f),
        leftHip: PosePoint = PosePoint(.085f, .50f),
        rightHip: PosePoint = PosePoint(-.085f, .50f),
        leftKnee: PosePoint = PosePoint(.09f, .74f),
        rightKnee: PosePoint = PosePoint(-.09f, .74f),
        leftAnkle: PosePoint = PosePoint(.10f, 1.0f),
        rightAnkle: PosePoint = PosePoint(-.10f, 1.0f),
    ): Map<Int, PosePoint> = mapOf(
        PoseJoint.NOSE to nose,
        PoseJoint.LEFT_EAR to leftEar, PoseJoint.RIGHT_EAR to rightEar,
        PoseJoint.LEFT_SHOULDER to leftShoulder, PoseJoint.RIGHT_SHOULDER to rightShoulder,
        PoseJoint.LEFT_ELBOW to leftElbow, PoseJoint.RIGHT_ELBOW to rightElbow,
        PoseJoint.LEFT_WRIST to leftWrist, PoseJoint.RIGHT_WRIST to rightWrist,
        PoseJoint.LEFT_HIP to leftHip, PoseJoint.RIGHT_HIP to rightHip,
        PoseJoint.LEFT_KNEE to leftKnee, PoseJoint.RIGHT_KNEE to rightKnee,
        PoseJoint.LEFT_ANKLE to leftAnkle, PoseJoint.RIGHT_ANKLE to rightAnkle,
    )
}
