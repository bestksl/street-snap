package dev.streetsnap.app

import dev.streetsnap.app.composition.CompositionOverlay
import dev.streetsnap.app.composition.CompositionSuggestion
import dev.streetsnap.app.pose.PoseGuide
import dev.streetsnap.app.pose.PoseUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiGuidanceLineTest {

    private fun pose(
        ready: Boolean = false,
        personVisible: Boolean = true,
        hint: String? = null,
    ) = PoseUiState(
        live = true,
        guide = PoseGuide(
            definitionId = "test",
            personVisible = personVisible,
            score = if (ready) 1f else 0f,
            ready = ready,
            hint = hint,
            sample = null,
            achievedAtMs = 0L,
        ),
    )

    private fun composition(text: String = "向左侧移动") =
        CompositionOverlay(suggestion = CompositionSuggestion(
            .1f, .1f, .9f, .9f, modelText = text), expiresAtMs = 0L)

    @Test
    fun `nothing active returns null`() {
        assertNull(AiGuidanceLine.select(
            false, false, false, null, null, null, false, null))
    }

    @Test
    fun `focus feedback outranks pose and composition`() {
        val line = AiGuidanceLine.select(
            isFocusing = true, isFocusLocked = false, focusFailed = false,
            pose = pose(), poseTitle = "单手举高", poseInvite = null,
            aligned = false, composition = composition())
        assertEquals("对焦中", line?.text)
        assertEquals(AiGuidanceTone.FOCUS, line?.tone)
    }

    @Test
    fun `focus lock shows AF-L sentence`() {
        val line = AiGuidanceLine.select(
            false, true, false, null, null, null, false, null)
        assertEquals("对焦已锁定 · 点 AF-L 解除", line?.text)
    }

    @Test
    fun `ready pose wins over composition and shows green tone`() {
        val line = AiGuidanceLine.select(
            false, false, false,
            pose = pose(ready = true), poseTitle = "单手举高", poseInvite = null,
            aligned = false, composition = composition())
        assertEquals("姿势到位 · 保持", line?.text)
        assertEquals(AiGuidanceTone.READY, line?.tone)
    }

    @Test
    fun `pose hint beats pose title`() {
        val line = AiGuidanceLine.select(
            false, false, false,
            pose = pose(hint = "手腕再抬高一点"), poseTitle = "单手举高", poseInvite = null,
            aligned = false, composition = null)
        assertEquals("手腕再抬高一点", line?.text)
    }

    @Test
    fun `person not visible prompts full-body framing`() {
        val line = AiGuidanceLine.select(
            false, false, false,
            pose = pose(personVisible = false), poseTitle = "单手举高", poseInvite = null,
            aligned = false, composition = null)
        assertEquals("把人放进画面，头到脚都入镜", line?.text)
    }

    @Test
    fun `aligned composition shows ready line even without pose`() {
        val line = AiGuidanceLine.select(
            false, false, false, null, null, null,
            aligned = true, composition = composition())
        assertEquals("构图到位 · 可以拍了", line?.text)
        assertEquals(AiGuidanceTone.READY, line?.tone)
    }

    @Test
    fun `composition action text falls back to model text and keeps detail`() {
        val line = AiGuidanceLine.select(
            false, false, false, null, null, null,
            aligned = false, composition = composition("主体偏右"))
        assertEquals("主体偏右", line?.text)
        assertEquals("主体偏右", line?.detail)
    }
}
