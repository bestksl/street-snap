package dev.streetsnap.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T72: for every catalogue pose we synthesise three bodies — met, halfway, neutral — and
 * assert the coach's hysteresis, hinting and missing-joint behaviour. Geometry mirrors the
 * neutral template in PoseCatalog (torso = 0.22 units). These are JVM geometric checks, not
 * photographic-truth evidence; real-subject calibration stays experimental/not-verified.
 */
class PoseCoachTest {

    private fun pose(
        timestampMs: Long = 0L,
        block: MutableMap<Int, PosePoint>.() -> Unit = {},
    ): PoseSample {
        val joints = mutableMapOf(
            PoseJoint.NOSE to PosePoint(0f, .12f),
            PoseJoint.LEFT_EAR to PosePoint(.065f, .075f),
            PoseJoint.RIGHT_EAR to PosePoint(-.065f, .075f),
            PoseJoint.LEFT_SHOULDER to PosePoint(.13f, .28f),
            PoseJoint.RIGHT_SHOULDER to PosePoint(-.13f, .28f),
            PoseJoint.LEFT_ELBOW to PosePoint(.17f, .42f),
            PoseJoint.RIGHT_ELBOW to PosePoint(-.17f, .42f),
            PoseJoint.LEFT_WRIST to PosePoint(.16f, .56f),
            PoseJoint.RIGHT_WRIST to PosePoint(-.16f, .56f),
            PoseJoint.LEFT_HIP to PosePoint(.085f, .50f),
            PoseJoint.RIGHT_HIP to PosePoint(-.085f, .50f),
            PoseJoint.LEFT_KNEE to PosePoint(.09f, .74f),
            PoseJoint.RIGHT_KNEE to PosePoint(-.09f, .74f),
            PoseJoint.LEFT_ANKLE to PosePoint(.10f, 1.0f),
            PoseJoint.RIGHT_ANKLE to PosePoint(-.10f, 1.0f),
        )
        joints.block()
        return PoseSample(List(33) { index -> joints[index] ?: PosePoint(0f, 0f, visibility = 0f) }, timestampMs)
    }

    private fun absent(): PoseSample =
        PoseSample(List(33) { PosePoint(0f, 0f, visibility = 0f) }, 0L)

    private fun runFor(definition: PoseDefinition, sample: PoseSample, frames: Int = 8): PoseGuide {
        val coach = PoseCoach()
        coach.select(definition)
        var guide = coach.observe(sample, 0L)
        repeat(frames - 1) { index -> guide = coach.observe(sample, (index + 1) * 100L) }
        return guide
    }

    private fun assertReady(definition: PoseDefinition, sample: PoseSample) {
        val guide = runFor(definition, sample)
        assertTrue(guide.ready)
        assertTrue(guide.score >= PoseCoach.SCORE_READY)
        assertNull(guide.hint)
    }

    private fun assertNotReady(definition: PoseDefinition, sample: PoseSample, minScore: Float = 0f) {
        val guide = runFor(definition, sample)
        assertFalse(guide.ready)
        assertTrue("score ${guide.score} below floor $minScore", guide.score >= minScore)
        assertTrue(guide.score < PoseCoach.SCORE_READY)
    }

    private fun byId(id: String) = PoseCatalog.poses.first { it.id == id }

    // ---- one arm up -------------------------------------------------------------

    @Test
    fun oneArmUp_metRight() = assertReady(byId("one_arm_up"), pose {
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.11f, .12f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(-.085f, -.05f)
    })

    @Test
    fun oneArmUp_metLeft_isMirrored() = assertReady(byId("one_arm_up"), pose {
        this[PoseJoint.LEFT_ELBOW] = PosePoint(.11f, .12f)
        this[PoseJoint.LEFT_WRIST] = PosePoint(.085f, -.05f)
    })

    @Test
    fun oneArmUp_halfwayBentElbow_notReady() = assertNotReady(byId("one_arm_up"), pose {
        // Wrist high but the elbow stays bent (~131°): lift satisfied, extension halfway,
        // and it still beats the hanging left arm so the coach picks this side.
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.20f, .20f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(-.18f, .05f)
    }, minScore = .3f)

    @Test
    fun oneArmUp_neutral_notReady() = assertNotReady(byId("one_arm_up"), pose())

    // ---- both arms up -----------------------------------------------------------

    @Test
    fun bothArmsUp_met() = assertReady(byId("both_arms_up"), pose {
        this[PoseJoint.LEFT_ELBOW] = PosePoint(.11f, .12f)
        this[PoseJoint.LEFT_WRIST] = PosePoint(.085f, -.05f)
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.11f, .12f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(-.085f, -.05f)
    })

    @Test
    fun bothArmsUp_shoulderHeight_notReady() = assertNotReady(byId("both_arms_up"), pose {
        this[PoseJoint.LEFT_WRIST] = PosePoint(.15f, .24f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(-.15f, .24f)
    }, minScore = .3f)

    // ---- hand on head -----------------------------------------------------------

    @Test
    fun handOnHead_met() = assertReady(byId("hand_on_head"), pose {
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.24f, .18f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(.02f, .10f)
    })

    @Test
    fun handOnHead_halfway_notReady() = assertNotReady(byId("hand_on_head"), pose {
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.15f, .22f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(.09f, .19f)
    }, minScore = .25f)

    // ---- hands on hips ----------------------------------------------------------

    @Test
    fun handsOnHips_met() = assertReady(byId("hands_on_hips"), pose {
        this[PoseJoint.LEFT_ELBOW] = PosePoint(.22f, .40f)
        this[PoseJoint.LEFT_WRIST] = PosePoint(.07f, .49f)
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.22f, .40f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(-.07f, .49f)
    })

    @Test
    fun handsOnHips_halfway_notReady() = assertNotReady(byId("hands_on_hips"), pose {
        this[PoseJoint.LEFT_ELBOW] = PosePoint(.16f, .42f)
        this[PoseJoint.LEFT_WRIST] = PosePoint(.13f, .52f)
        this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.16f, .42f)
        this[PoseJoint.RIGHT_WRIST] = PosePoint(-.13f, .52f)
    }, minScore = .3f)

    // ---- twist ------------------------------------------------------------------

    private fun twistedBody(degrees: Float): PoseSample = pose {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        // Shoulder line rotates; hips stay level. Image convention: y grows down.
        this[PoseJoint.LEFT_SHOULDER] = PosePoint(.13f * cos, .28f - .13f * sin)
        this[PoseJoint.RIGHT_SHOULDER] = PosePoint(-.13f * cos, .28f + .13f * sin)
    }

    @Test
    fun twist_thirtyDegrees_met() = assertReady(byId("twist"), twistedBody(30f))

    @Test
    fun twist_fifteenDegrees_notReady() = assertNotReady(byId("twist"), twistedBody(15f), minScore = .2f)

    @Test
    fun twist_neutral_notReady() = assertNotReady(byId("twist"), pose())

    // ---- head tilt --------------------------------------------------------------

    @Test
    fun tiltHead_chinUp_met() = assertReady(byId("tilt_head"), pose {
        this[PoseJoint.NOSE] = PosePoint(0f, -.02f)
    })

    @Test
    fun tiltHead_halfway_notReady() = assertNotReady(byId("tilt_head"), pose {
        // Nose lifted partway toward the ear line: proxy ~0.12, halfway through the ramp.
        this[PoseJoint.NOSE] = PosePoint(0f, .06f)
    }, minScore = .4f)

    @Test
    fun tiltHead_singleEar_chinUp_stillScores() = assertReady(byId("tilt_head"), pose {
        this[PoseJoint.RIGHT_EAR] = PosePoint(-.065f, .075f, visibility = 0f)
        this[PoseJoint.NOSE] = PosePoint(0f, -.02f)
    })

    // ---- look back --------------------------------------------------------------

    @Test
    fun lookBack_turned_met() = assertReady(byId("look_back"), pose {
        this[PoseJoint.NOSE] = PosePoint(-.07f, .12f)
    })

    @Test
    fun lookBack_halfway_notReady() = assertNotReady(byId("look_back"), pose {
        this[PoseJoint.NOSE] = PosePoint(-.035f, .12f)
    }, minScore = .15f)

    @Test
    fun lookBack_singleEar_profileTurn_stillScores() = assertReady(byId("look_back"), pose {
        // Turning the head hides the far ear; one ear plus the shoulder-width fallback
        // must keep scoring instead of surfacing a "cannot see face" failure.
        this[PoseJoint.RIGHT_EAR] = PosePoint(-.065f, .075f, visibility = 0f)
        this[PoseJoint.NOSE] = PosePoint(-.07f, .12f)
    })

    @Test
    fun lookBack_noEars_asksForFace() {
        val guide = runFor(byId("look_back"), pose {
            this[PoseJoint.LEFT_EAR] = PosePoint(.065f, .075f, visibility = 0f)
            this[PoseJoint.RIGHT_EAR] = PosePoint(-.065f, .075f, visibility = 0f)
        })
        assertFalse(guide.ready)
        assertTrue(guide.hint?.contains("头转") == true)
    }

    // ---- weight on back leg -----------------------------------------------------

    @Test
    fun weightBack_met() = assertReady(byId("weight_back"), pose {
        // Ankles shifted from the hips; front leg near-straight (~166°), back leg bent.
        this[PoseJoint.LEFT_KNEE] = PosePoint(.095f, .72f)
        this[PoseJoint.LEFT_ANKLE] = PosePoint(.10f, .96f)
        this[PoseJoint.RIGHT_KNEE] = PosePoint(-.06f, .70f)
        this[PoseJoint.RIGHT_ANKLE] = PosePoint(-.18f, .96f)
    })

    @Test
    fun weightBack_neutral_notReady() {
        // Even neutral stance must never read as the pose (knees read straight, no shift).
        assertNotReady(byId("weight_back"), pose())
    }

    // ---- catalogue sanity -------------------------------------------------------

    @Test
    fun catalogue_hasEightPosesWithUniqueTitles() {
        assertEquals(8, PoseCatalog.poses.size)
        assertEquals(8, PoseCatalog.poses.map { it.id }.toSet().size)
        PoseCatalog.poses.forEach { definition ->
            assertTrue(definition.title.isNotEmpty())
            assertTrue(definition.invite.isNotEmpty())
            // Every template can anchor on the four torso joints and covers the nose.
            val projected = definition.template.projectTo(pose())
            assertNotNull(projected)
            assertTrue(projected!!.containsKey(PoseJoint.NOSE))
        }
    }

    @Test
    fun personVisible_requiresTorso() {
        assertTrue(PoseCatalog.personVisible(pose()))
        assertFalse(PoseCatalog.personVisible(absent()))
    }

    // ---- missing joints ---------------------------------------------------------

    @Test
    fun missingTorso_asksToStepBack() {
        val guide = runFor(byId("both_arms_up"), absent())
        assertFalse(guide.personVisible)
        assertFalse(guide.ready)
        assertTrue(guide.hint?.contains("身体") == true)
    }

    @Test
    fun missingArms_surfacesVisibleFalseHint() {
        val torsoOnly = pose {
            listOf(PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW, PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST)
                .forEach { this[it] = PosePoint(0f, 0f, visibility = 0f) }
        }
        val guide = runFor(byId("both_arms_up"), torsoOnly)
        assertTrue(guide.personVisible)
        assertFalse(guide.ready)
        assertTrue(guide.hint?.contains("手臂") == true)
    }

    @Test
    fun nullSample_isSilentAndNotReady() {
        val coach = PoseCoach()
        val guide = coach.observe(null, 0L)
        assertFalse(guide.personVisible)
        assertFalse(guide.ready)
        assertNull(guide.hint)
    }

    // ---- hysteresis and rotation ------------------------------------------------

    @Test
    fun holdHysteresis_needsFourFramesAndEmitsEdgeOnce() {
        val coach = PoseCoach()
        coach.select(byId("both_arms_up"))
        val met = pose {
            this[PoseJoint.LEFT_ELBOW] = PosePoint(.11f, .12f)
            this[PoseJoint.LEFT_WRIST] = PosePoint(.085f, -.05f)
            this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.11f, .12f)
            this[PoseJoint.RIGHT_WRIST] = PosePoint(-.085f, -.05f)
        }
        repeat(3) { frame ->
            assertFalse(coach.observe(met, frame * 100L).ready)
        }
        val achieved = coach.observe(met, 300L)
        assertTrue(achieved.ready)
        assertEquals(300L, achieved.achievedAtMs)
        // While held, the edge does not re-fire.
        assertEquals(0L, coach.observe(met, 400L).achievedAtMs)
    }

    @Test
    fun dropHysteresis_holdsForThreeWeakFrames() {
        val coach = PoseCoach()
        coach.select(byId("both_arms_up"))
        val met = pose {
            this[PoseJoint.LEFT_ELBOW] = PosePoint(.11f, .12f)
            this[PoseJoint.LEFT_WRIST] = PosePoint(.085f, -.05f)
            this[PoseJoint.RIGHT_ELBOW] = PosePoint(-.11f, .12f)
            this[PoseJoint.RIGHT_WRIST] = PosePoint(-.085f, -.05f)
        }
        repeat(4) { frame -> coach.observe(met, frame * 100L) }
        assertTrue(coach.observe(met, 400L).ready)
        // Two weak frames stay ready (rearm band), the third drops it.
        coach.observe(pose(), 500L)
        assertTrue(coach.observe(pose(), 600L).ready)
        assertFalse(coach.observe(pose(), 700L).ready)
    }

    @Test
    fun nextPose_cyclesAllEightAndLoops() {
        val coach = PoseCoach()
        // next() advances from the initially selected first pose, so the first eight
        // next() calls visit poses 2..8 then 1, and the ninth lands on pose 2 again.
        val expected = PoseCatalog.poses.drop(1).map { it.id } + PoseCatalog.poses.first().id
        val visited = generateSequence { coach.next() }.take(8).map { it.id }.toList()
        assertEquals(expected, visited)
        assertEquals(PoseCatalog.poses[1].id, coach.next().id)
    }

    @Test
    fun selectSamePose_isNoOp() {
        val coach = PoseCoach()
        val first = PoseCatalog.poses.first()
        coach.select(first)
        coach.select(first) // no-op, must not throw or change state
        assertEquals(first.id, coach.current().id)
    }
}
