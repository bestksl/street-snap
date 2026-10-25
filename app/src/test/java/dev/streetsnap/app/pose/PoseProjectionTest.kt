package dev.streetsnap.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T109: the target figure is bone-length-retargeted onto the observed body — torso anchors
 * pin exactly, visible limbs keep the person's own segment lengths while following the
 * template direction, and hidden segments fall back to the similarity projection. JVM
 * geometry only; it proves the mapping, not fit on photographed subjects.
 */
class PoseProjectionTest {

    private val oneArmUp = PoseCatalog.poses.first { it.id == "one_arm_up" }
    // tilt_head only overrides head joints, so its limbs use neutralJoints proportions.
    private val neutralPose = PoseCatalog.poses.first { it.id == "tilt_head" }

    /** Mirrors PoseCatalog.neutralJoints — the same proportions the templates are built on. */
    private fun sample(block: MutableMap<Int, PosePoint>.() -> Unit = {}): PoseSample {
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
        return PoseSample(List(33) { joints[it] ?: PosePoint(0f, 0f, visibility = 0f) }, 0L)
    }

    private fun invisible(vararg indices: Int): MutableMap<Int, PosePoint>.() -> Unit = {
        indices.forEach { this[it] = PosePoint(this[it]!!.x, this[it]!!.y, visibility = 0f) }
    }

    @Test
    fun torsoAnchors_pinToObservedExactly() {
        val body = sample()
        val projected = oneArmUp.template.projectTo(body)!!
        assertEquals(body.points[PoseJoint.LEFT_SHOULDER], projected[PoseJoint.LEFT_SHOULDER])
        assertEquals(body.points[PoseJoint.RIGHT_SHOULDER], projected[PoseJoint.RIGHT_SHOULDER])
        assertEquals(body.points[PoseJoint.LEFT_HIP], projected[PoseJoint.LEFT_HIP])
        assertEquals(body.points[PoseJoint.RIGHT_HIP], projected[PoseJoint.RIGHT_HIP])
    }

    @Test
    fun neutralBody_reproducesObservedProportions() {
        // Observed body with the template's own proportions: the retarget must map every
        // drawn joint back onto the observed position.
        val body = sample()
        val projected = neutralPose.template.projectTo(body)!!
        listOf(
            PoseJoint.NOSE,
            PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW,
            PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST,
            PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE,
            PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE,
        ).forEach { joint ->
            val expected = body.points[joint]
            val actual = projected.getValue(joint)
            assertEquals("joint $joint x", expected.x, actual.x, .005f)
            assertEquals("joint $joint y", expected.y, actual.y, .005f)
        }
    }

    @Test
    fun visibleArm_usesObservedBoneLengths() {
        // Left arm held horizontally with long upper-arm and short forearm segments.
        val body = sample {
            this[PoseJoint.LEFT_ELBOW] = PosePoint(.20f, .28f)
            this[PoseJoint.LEFT_WRIST] = PosePoint(.25f, .28f)
        }
        val projected = oneArmUp.template.projectTo(body)!!
        val shoulder = projected.getValue(PoseJoint.LEFT_SHOULDER)
        val elbow = projected.getValue(PoseJoint.LEFT_ELBOW)
        val wrist = projected.getValue(PoseJoint.LEFT_WRIST)
        assertEquals(.07f, dist(shoulder, elbow), 1e-4f)
        assertEquals(.05f, dist(elbow, wrist), 1e-4f)
        // The chain hangs from the real shoulder along the template's near-vertical arm
        // direction instead of floating out at the observed horizontal angle.
        assertEquals(elbow.x, wrist.x, 5e-3f)
    }

    @Test
    fun hiddenElbow_entireChainFallsBackToBaseProjection() {
        val body = sample(invisible(PoseJoint.LEFT_ELBOW, PoseJoint.LEFT_WRIST))
        val projected = body.templateLet()
        // No retarget for this arm: positions come straight from the similarity projection
        // (shoulder anchors remain exact).
        val ls = body.points[PoseJoint.LEFT_SHOULDER]
        val baseElbow = PosePoint(.17f, .42f) // template proportions at scale 1, no lean
        assertEquals(baseElbow.x, projected.getValue(PoseJoint.LEFT_ELBOW).x, .005f)
        assertEquals(baseElbow.y, projected.getValue(PoseJoint.LEFT_ELBOW).y, .005f)
        assertEquals(ls, projected[PoseJoint.LEFT_SHOULDER])
    }

    @Test
    fun hiddenWrist_keepsChainConnectedAtTemplateLength() {
        val body = sample {
            this[PoseJoint.LEFT_ELBOW] = PosePoint(.20f, .28f)
            this[PoseJoint.LEFT_WRIST] = PosePoint(.20f, .28f, visibility = 0f)
        }
        val projected = oneArmUp.template.projectTo(body)!!
        val elbow = projected.getValue(PoseJoint.LEFT_ELBOW)
        val wrist = projected.getValue(PoseJoint.LEFT_WRIST)
        val templateSegment = .1404f // neutralJoints elbow(.17,.42) -> wrist(.16,.56)
        assertEquals(templateSegment, dist(elbow, wrist), 5e-3f)
    }

    @Test
    fun head_usesObservedHeadLength() {
        val body = sample { this[PoseJoint.NOSE] = PosePoint(0f, .18f) } // longer neck/head reach
        val projected = oneArmUp.template.projectTo(body)!!
        val shoulderMid = midpoint(
            body.points[PoseJoint.LEFT_SHOULDER], body.points[PoseJoint.RIGHT_SHOULDER])
        // Template nose direction is straight up from the shoulder midpoint.
        val nose = projected.getValue(PoseJoint.NOSE)
        assertEquals(.10f, dist(shoulderMid, nose), 1e-4f)
        assertEquals(shoulderMid.x, nose.x, 1e-4f)
    }

    @Test
    fun missingTorso_returnsNull() {
        val body = sample(invisible(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER,
            PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP))
        assertNull(oneArmUp.template.projectTo(body))
    }

    @Test
    fun everyCataloguePose_projectsWithAllDrawnJoints() {
        val body = sample()
        PoseCatalog.poses.forEach { definition ->
            val projected = definition.template.projectTo(body)
            assertNotNull(projected)
            assertTrue(projected!!.keys.containsAll(setOf(
                PoseJoint.NOSE,
                PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER,
                PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW,
                PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST,
                PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP,
                PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE,
                PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE,
            )))
        }
    }

    private fun PoseSample.templateLet(): Map<Int, PosePoint> =
        oneArmUp.template.projectTo(this)!!
}
