package dev.streetsnap.app

import dev.streetsnap.app.composition.CompositionOverlay
import dev.streetsnap.app.pose.PoseUiState

/**
 * T123: the single recognition line shown in the black control cluster (framed
 * portrait) instead of a card floating over the 3:4 picture. Exactly one of
 * focus / pose / composition occupies it at a time.
 */
internal enum class AiGuidanceTone { NORMAL, READY, FOCUS }

internal data class AiGuidanceLine(
    val text: String,
    val tone: AiGuidanceTone = AiGuidanceTone.NORMAL,
    /** Full sentence kept for semantics; the visible line stays single-line. */
    val detail: String? = null,
) {
    companion object {

        /**
         * Priority: live focus feedback first (it blocks the shot), then the pose
         * assistant (its bottom card used to cover the subject), then composition.
         * Returns null when no recognition text is active, so the band keeps its
         * quiet idle state.
         */
        fun select(
            isFocusing: Boolean,
            isFocusLocked: Boolean,
            focusFailed: Boolean,
            pose: PoseUiState?,
            poseTitle: String?,
            poseInvite: String?,
            aligned: Boolean,
            composition: CompositionOverlay?,
        ): AiGuidanceLine? {
            if (isFocusing) return AiGuidanceLine("对焦中", AiGuidanceTone.FOCUS)
            if (isFocusLocked) return AiGuidanceLine("对焦已锁定 · 点 AF-L 解除", AiGuidanceTone.FOCUS)
            if (focusFailed) return AiGuidanceLine("未合焦 · 可重试或直接拍摄", AiGuidanceTone.FOCUS)
            if (pose != null && pose.live) {
                val guide = pose.guide
                if (guide?.ready == true)
                    return AiGuidanceLine("姿势到位 · 保持", AiGuidanceTone.READY)
                val text = when {
                    guide?.personVisible == false -> "把人放进画面，头到脚都入镜"
                    guide?.hint != null -> guide.hint
                    poseTitle != null -> poseTitle
                    else -> poseInvite
                }
                return AiGuidanceLine(text ?: return null)
            }
            if (aligned) return AiGuidanceLine("构图到位 · 可以拍了", AiGuidanceTone.READY)
            val suggestion = composition?.suggestion ?: return null
            return AiGuidanceLine(suggestion.actionText ?: suggestion.modelText,
                detail = suggestion.modelText)
        }
    }
}
