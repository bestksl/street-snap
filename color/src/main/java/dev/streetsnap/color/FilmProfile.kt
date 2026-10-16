package dev.streetsnap.color

/**
 * A film card is an independently authored style recipe, not a copy of a film stock,
 * camera firmware, or another app's artwork. Numbers describe a film-inspired family;
 * they do not set the sensor's ISO. The swatches are only UI accents.
 */
data class FilmProfile(
    val id: String,
    val displayName: String,
    val description: String,
    val defaults: ColorRecipe,
    val swatchStart: Long,
    val swatchEnd: Long,
) {
    val basePreset: ColorPreset get() = defaults.preset
}

object FilmRegistry {
    val entries: List<FilmProfile> = listOf(
        FilmProfile(
            "DAY_01", "日光 250", "微暖日光，柔和反差与细颗粒",
            ColorRecipe(
                preset = ColorPreset.SUNNY, intensity = .8f, grain = .18f, vignette = .08f,
                saturation = -.08f, contrast = -.12f, highlights = -.2f, shadows = .12f,
                sharpness = -.08f, clarity = -.06f,
            ),
            0xFFF4C94D, 0xFFB28135,
        ),
        FilmProfile(
            "NIGHT_01", "钨光 500", "冷影暖光，为夜街保留明暗层次",
            ColorRecipe(
                preset = ColorPreset.NEGATIVE, intensity = .92f, grain = .3f, vignette = .16f,
                saturation = -.14f, hue = -.07f, contrast = .08f, brightness = -.04f,
                highlights = -.32f, shadows = .14f, sharpness = -.1f, clarity = -.08f,
            ),
            0xFF315E88, 0xFF14243A,
        ),
        FilmProfile(
            "CHROME_01", "反转 100", "浓郁色彩，紧实暗部与清晰轮廓",
            ColorRecipe(
                preset = ColorPreset.POSITIVE, intensity = .95f, grain = .08f, vignette = .1f,
                saturation = .08f, contrast = .12f, highlights = -.1f, shadows = -.08f,
                sharpness = .12f, clarity = .08f,
            ),
            0xFFD96932, 0xFFF4C748,
        ),
        FilmProfile(
            "NEG_01", "日光 50", "明净暖色，低颗粒与利落的晴日层次",
            ColorRecipe(
                preset = ColorPreset.SUNNY, intensity = .65f, grain = .05f, vignette = .06f,
                saturation = .03f, contrast = .06f, brightness = .02f, highlights = -.16f,
                shadows = .04f, sharpness = .1f, clarity = .06f,
            ),
            0xFFEC913E, 0xFFDAAE70,
        ),
        FilmProfile(
            "PASTEL_01", "柔彩 200", "轻淡青绿，舒展中间调与柔软细节",
            ColorRecipe(
                preset = ColorPreset.POWDER_BLUE, intensity = .72f, grain = .14f, vignette = .04f,
                saturation = -.2f, hue = -.025f, contrast = -.16f, brightness = .04f,
                highlights = -.18f, shadows = .18f, sharpness = -.12f, clarity = -.16f,
            ),
            0xFF579B85, 0xFFDBE1CE,
        ),
        FilmProfile(
            "MONO_01", "银盐 400", "粗颗粒黑白，深黑与留白之间",
            ColorRecipe(
                preset = ColorPreset.MONO, grain = .34f, vignette = .12f, contrast = .08f,
                highlights = -.14f, shadows = .06f, sharpness = -.06f, clarity = .08f,
            ),
            0xFFD6B653, 0xFF496F58,
        ),
        FilmProfile(
            "PORTRAIT_01", "人像 400", "柔暖肤色，低反差与舒缓高光",
            ColorRecipe(
                preset = ColorPreset.NEGATIVE, intensity = .7f, grain = .16f, vignette = .05f,
                saturation = -.06f, hue = .02f, contrast = -.1f, brightness = .03f,
                highlights = -.25f, shadows = .1f, sharpness = -.08f, clarity = -.12f,
            ),
            0xFF82957B, 0xFFCCB5A2,
        ),
    )

    fun find(id: String?): FilmProfile? = entries.firstOrNull { it.id == id }
}
