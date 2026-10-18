package dev.streetsnap.color

/**
 * Named after real photographic stocks; the rendering recipes are independently authored
 * interpretations, not manufacturer scans or extracted camera data. Stock speed is
 * descriptive and never changes the phone sensor ISO. IDs remain stable for upgrades.
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
            "DAY_01", "VISION3 250D", "Kodak 5207 · 日光电影负片，微暖、柔和反差与细颗粒",
            ColorRecipe(
                preset = ColorPreset.SUNNY, intensity = .8f, grain = .18f, vignette = .08f,
                saturation = -.08f, contrast = -.12f, highlights = -.2f, shadows = .12f,
                sharpness = -.08f, clarity = -.06f,
            ),
            0xFFF4C94D, 0xFFB28135,
        ),
        FilmProfile(
            "NIGHT_01", "VISION3 500T", "Kodak 5219 · 钨光电影负片，冷影暖光与较明显颗粒",
            ColorRecipe(
                preset = ColorPreset.NEGATIVE, intensity = .92f, grain = .3f, vignette = .16f,
                saturation = -.14f, hue = -.07f, contrast = .08f, brightness = -.04f,
                highlights = -.32f, shadows = .14f, sharpness = -.1f, clarity = -.08f,
            ),
            0xFF315E88, 0xFF14243A,
        ),
        FilmProfile(
            "CHROME_01", "EKTAR 100", "Kodak · 低感彩色负片，饱和色彩与清晰细节",
            ColorRecipe(
                preset = ColorPreset.POSITIVE, intensity = .95f, grain = .08f, vignette = .1f,
                saturation = .08f, contrast = .12f, highlights = -.1f, shadows = -.08f,
                sharpness = .12f, clarity = .08f,
            ),
            0xFFD96932, 0xFFF4C748,
        ),
        FilmProfile(
            "NEG_01", "VISION3 50D", "Kodak 5203 · 日光电影负片，细颗粒、明净暖色",
            ColorRecipe(
                preset = ColorPreset.SUNNY, intensity = .65f, grain = .05f, vignette = .06f,
                saturation = .03f, contrast = .06f, brightness = .02f, highlights = -.16f,
                shadows = .04f, sharpness = .1f, clarity = .06f,
            ),
            0xFFEC913E, 0xFFDAAE70,
        ),
        FilmProfile(
            "PASTEL_01", "FUJICOLOR C200", "Fujifilm · 日光彩色负片，清淡青绿与舒展中间调",
            ColorRecipe(
                preset = ColorPreset.POWDER_BLUE, intensity = .72f, grain = .14f, vignette = .04f,
                saturation = -.2f, hue = -.025f, contrast = -.16f, brightness = .04f,
                highlights = -.18f, shadows = .18f, sharpness = -.12f, clarity = -.16f,
            ),
            0xFF579B85, 0xFFDBE1CE,
        ),
        FilmProfile(
            "MONO_01", "TRI-X 400", "Kodak 400TX · 经典黑白负片，颗粒与深黑",
            ColorRecipe(
                preset = ColorPreset.MONO, grain = .34f, vignette = .12f, contrast = .08f,
                highlights = -.14f, shadows = .06f, sharpness = -.06f, clarity = .08f,
            ),
            // Monochrome stock: a neutral gray ramp (light -> dark), never the gold/green placeholders.
            0xFFC6C6C6, 0xFF101010,
        ),
        FilmProfile(
            "PORTRAIT_01", "PORTRA 400", "Kodak · 人像彩色负片，柔暖肤色与舒缓高光",
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
