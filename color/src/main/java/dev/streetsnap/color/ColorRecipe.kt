package dev.streetsnap.color

enum class ColorPreset(val displayName: String) {
    ORIGINAL("原色"), POSITIVE("正片"), NEGATIVE("负片"), POWDER_BLUE("粉末蓝"),
    MONO("高反差黑白"), SUNNY("晴街"), CUSTOM("自定义"),
    CLASSIC_CHROME("CC · 经典正片"), CLASSIC_NEGATIVE("NC · 经典负片"),
    NOSTALGIC_NEGATIVE("NA · 怀旧负片"), ACROS("ACROS · 黑白"),
    LUMIX_NATURAL("自然"), LUMIX_MONO("L.单色")
}

/**
 * Immutable settings shared by preview and photos. Creative adjustments are signed [-1, 1];
 * intensity/grain/vignette/HDF strength use [0, 1]. WB drift follows A/B and G/M camera axes.
 * The first four arguments retain their order so existing recipe callers stay compatible.
 */
data class ColorRecipe(
    val preset: ColorPreset = ColorPreset.ORIGINAL,
    val intensity: Float = 1f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val contrast: Float = 0f,
    val brightness: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f,
    val clarity: Float = 0f,
    val hdfEnabled: Boolean = false,
    val hdfStrength: Float = .4f,
    // Independent HDR look: filmic highlight shoulder plus a wide outer halo.
    // Decoupled from HDF diffusion; see docs/hdr-film-research.md for the parameter model.
    val hdrEnabled: Boolean = false,
    val hdrStrength: Float = .6f,
    val whiteBalanceAmber: Int = 0,
    val whiteBalanceGreen: Int = 0,
) {
    init {
        require(intensity.isFinite() && intensity in 0f..1f) { "影调强度须在 0–1 之间" }
        require(grain.isFinite() && grain in 0f..1f) { "颗粒强度须在 0–1 之间" }
        require(vignette.isFinite() && vignette in 0f..1f) { "暗角强度须在 0–1 之间" }
        require(hdfStrength.isFinite() && hdfStrength in 0f..1f) { "柔光强度须在 0–1 之间" }
        require(hdrStrength.isFinite() && hdrStrength in 0f..1f) { "HDR 强度须在 0–1 之间" }
        for ((name, amount) in listOf(
            "饱和度" to saturation, "色相" to hue, "对比度" to contrast, "亮度" to brightness,
            "高光" to highlights, "阴影" to shadows, "锐度" to sharpness, "清晰度" to clarity,
        )) {
            require(amount.isFinite() && amount in -1f..1f) { "$name 须在 -1–1 之间" }
        }
        require(whiteBalanceAmber in -9..9 && whiteBalanceGreen in -9..9) { "白平衡偏移须在 -9–9 之间" }
    }

    /** Disabled controls have no effect; an original look alone is not enough to skip rendering. */
    val isIdentity: Boolean get() =
        (preset == ColorPreset.ORIGINAL || intensity == 0f) && grain == 0f && vignette == 0f &&
            saturation == 0f && hue == 0f && contrast == 0f && brightness == 0f &&
            highlights == 0f && shadows == 0f && sharpness == 0f && clarity == 0f &&
            (!hdfEnabled || hdfStrength == 0f) &&
            (!hdrEnabled || hdrStrength == 0f) && whiteBalanceAmber == 0 && whiteBalanceGreen == 0
}

class ColorProcessingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
