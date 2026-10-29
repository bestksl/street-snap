package dev.streetsnap.app.composition

/**
 * The rare wide-angle nudge, isolated as pure logic so its gating is unit-testable.
 *
 * It is deliberately hard to earn: the image statistics must read a small/far subject,
 * the lens must be wide, a long cooldown must have elapsed and only a fifth of eligible
 * draws survive. It never feeds back into crops, scores or gates.
 */
internal object CompositionEasterEgg {
    const val COOLDOWN_MS: Long = 90_000L

    fun decide(
        subjectLooksDistant: Boolean,
        origin: AdviceOrigin,
        focalLengthMm: Int,
        draw: Int,
        nowMs: Long,
        lastEggAtMs: Long,
    ): String? {
        if (origin != AdviceOrigin.IMAGE_ANALYSIS || !subjectLooksDistant || focalLengthMm !in 1..35) return null
        if (nowMs - lastEggAtMs < COOLDOWN_MS || draw % 5 != 0) return null
        val lines = listOf(
            "${focalLengthMm}mm 小彩蛋 · 主体有点远，凑近一步会更有存在感",
            "悄悄说 · 广角不怕近，往前走几步往往更有故事",
            "${focalLengthMm}mm 适合靠近了拍，大胆一点，也不必勉强",
            "彩蛋 · 再往前半步，街拍的临场感就出来了",
        )
        return lines[(draw / 5 + focalLengthMm) % lines.size]
    }
}
