package dev.streetsnap.app.composition

import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry

/**
 * One recommended look (a film stock or a preset) with the reason the UI must show.
 * [key] mirrors AppSettings.activeLookKey so de-dupe and dismiss memory share one identity.
 */
internal data class LookTarget(
    val filmId: String?,
    val preset: ColorPreset,
    val label: String,
    val reason: String,
) {
    val key: String get() = filmId?.let { "FILM:$it" } ?: preset.name
}

/**
 * SceneReadings → look mapping. Pure and deterministic by design. Ambiguous light still
 * abstains unless a single coarse signal (warmth/saturation) is decisive enough — the
 * Fujifilm "no scene icon = abstain" principle from docs/look-recommendation-research.md,
 * widened 2026-10-19 after the in-frame card became a quiet nameplate chip so the adviser
 * can afford to speak a little more often. Several readings must still agree on the target;
 * this is rule evidence, never a probability, so no confidence is shown.
 */
internal object LookRecommendations {
    /** Night only earns black-and-white when the contrast is actually there; flat darkness abstains. */
    const val NIGHT_CONTRAST = 0.16
    /** Share of the upper half reading as sky needed for a vivid positive-film suggestion. */
    const val SKY_STRONG = 0.25
    const val FOLIAGE_STRONG = 0.30
    const val FOLIAGE_MILD = 0.12
    const val LOW_SATURATION = 0.10
    const val FLAT_CONTRAST = 0.13
    /** Warm daylight needs visible colour before the positive-film mapping fires. */
    const val WARM_VIVID_SATURATION = 0.12
    /** A cool cast with some colour reads as a Classic Chrome street scene. */
    const val COOL_SCENE_SATURATION = 0.10

    private val monoTriX = requireNotNull(FilmRegistry.find("MONO_01"))

    fun from(reading: SceneReading): LookTarget? {
        return when {
            reading.dayPhase == DayPhase.NIGHT && reading.contrast >= NIGHT_CONTRAST ->
                LookTarget(monoTriX.id, monoTriX.basePreset, monoTriX.displayName,
                    "夜色反差大，${monoTriX.displayName}黑白更利落")
            reading.dayPhase == DayPhase.DUSK ->
                LookTarget(null, ColorPreset.CLASSIC_NEGATIVE, ColorPreset.CLASSIC_NEGATIVE.displayName,
                    "黄昏暖光，Classic Negative 更有氛围")
            // Warm indoor light that is too weak/bright to read as dusk.
            reading.dayPhase == DayPhase.UNKNOWN && reading.warmth == ColorWarmth.WARM ->
                LookTarget(null, ColorPreset.CLASSIC_NEGATIVE, ColorPreset.CLASSIC_NEGATIVE.displayName,
                    "暖光柔和，Classic Negative 更耐看")
            reading.dayPhase == DayPhase.DAY && reading.skyRatioUpper >= SKY_STRONG ->
                LookTarget(null, ColorPreset.POSITIVE, ColorPreset.POSITIVE.displayName,
                    if (reading.warmth == ColorWarmth.WARM) "晴光暖照，正片色彩更鲜亮"
                    else "晴空通透，正片蓝调更干净")
            // Vivid warm daylight without a large patch of sky still earns positive film.
            reading.dayPhase == DayPhase.DAY && reading.warmth == ColorWarmth.WARM &&
                reading.saturation >= WARM_VIVID_SATURATION ->
                LookTarget(null, ColorPreset.POSITIVE, ColorPreset.POSITIVE.displayName,
                    "暖晴鲜活，正片色彩更明快")
            reading.dayPhase != DayPhase.NIGHT &&
                reading.foliageRatioUpper >= FOLIAGE_STRONG && reading.skyRatioUpper < FOLIAGE_MILD &&
                reading.warmth != ColorWarmth.WARM ->
                LookTarget(null, ColorPreset.CLASSIC_CHROME, ColorPreset.CLASSIC_CHROME.displayName,
                    "冷绿街景，Classic Chrome 更克制")
            // Flat, low-colour light is checked before the cool-street rule: at the saturation
            // boundary both could match, and "pale and low-contrast" is the stronger evidence.
            (reading.dayPhase == DayPhase.DAY || reading.dayPhase == DayPhase.UNKNOWN) &&
                reading.saturation <= LOW_SATURATION && reading.contrast < FLAT_CONTRAST ->
                LookTarget(null, ColorPreset.POWDER_BLUE, ColorPreset.POWDER_BLUE.displayName,
                    "光色偏淡，粉末蓝影调更柔和")
            // Cool-toned street light without explicit foliage detection.
            reading.dayPhase != DayPhase.NIGHT && reading.warmth == ColorWarmth.COOL &&
                reading.saturation >= COOL_SCENE_SATURATION ->
                LookTarget(null, ColorPreset.CLASSIC_CHROME, ColorPreset.CLASSIC_CHROME.displayName,
                    "冷调街景，Classic Chrome 更克制")
            else -> null
        }
    }
}
