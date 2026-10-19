package dev.streetsnap.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.streetsnap.app.edge.EdgeModelCatalog
import dev.streetsnap.camera.WhiteBalanceMode
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.ColorRecipe
import dev.streetsnap.color.FilmRegistry
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.recipeStore by preferencesDataStore(name = "street_snap")

/** Each look keeps its own edits; white balance and diffusion remain camera-wide controls. */
data class LookSettings(
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
) {
    companion object {
        fun fromRecipe(recipe: ColorRecipe) = LookSettings(
            intensity = recipe.intensity, grain = recipe.grain, vignette = recipe.vignette,
            saturation = recipe.saturation, hue = recipe.hue, contrast = recipe.contrast,
            brightness = recipe.brightness, highlights = recipe.highlights, shadows = recipe.shadows,
            sharpness = recipe.sharpness, clarity = recipe.clarity,
        )
    }
}

data class AppSettings(
    val focalLength: Int = 28,
    val exposure: Int = 0,
    val focus: String = "AUTO",
    val focusDiopters: Float = 0f,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val grid: Boolean = true,
    val level: Boolean = true,
    // Viewfinder OSD cycle: OFF (default, pure viewfinder) / HISTOGRAM / PARAMS (histogram + exposure readout).
    val viewfinderOsd: String = ViewfinderOsd.OFF,
    val preset: String = "ORIGINAL",
    val filmId: String? = null,
    // null = the nameplate follows the active look family; "HIDDEN" = no nameplate; a
    // camera id = a manually pinned body. See CameraPreferences.nameplate.
    val mascotCamera: String? = null,
    val captureOrientation: String = CameraPreferences.DEFAULT_ORIENTATION,
    val compositionEnabled: Boolean = false,
    val compositionRealtime: Boolean = false,
    val compositionModel: String = EdgeModelCatalog.DEFAULT_MODEL_ID,
    val looks: Map<String, LookSettings> = emptyMap(),
    val lutName: String? = null,
    val hdfEnabled: Boolean = false,
    val hdfStrength: Float = .4f,
    // Ricoh-style HDF (Highlight Diffusion Filter): filmic highlight shoulder + wide halo,
    // off by default. JSON keys keep their legacy "hdr*" names for settings compatibility.
    val hdrEnabled: Boolean = false,
    val hdrStrength: Float = .6f,
    val whiteBalance: String = "AUTO",
    val whiteBalanceAmber: Int = 0,
    val whiteBalanceGreen: Int = 0,
    // Independent switch for the realtime look-suggestion card; on by default (T30).
    val lookAdviceEnabled: Boolean = true,
    // Independent switch for clipped-highlight/shadow exposure hints; on by default (T31).
    val exposureAdviceEnabled: Boolean = true,
    // T34: optional light haptic when framing reaches the suggested crop. Visual green
    // confirmation always shows; haptics are opt-in, and there is deliberately no sound.
    val alignmentHapticEnabled: Boolean = false,
    // look key → dismiss timestamp (epoch ms); adviser suppresses keys within a 7-day window.
    val lookDismissedAt: Map<String, Long> = emptyMap(),
    // Three consecutive dismisses flip lookAdviceEnabled off; trying/keeping a look resets it.
    val lookAdviceDismissStreak: Int = 0,
) {
    val activeLookKey: String get() = FilmRegistry.find(filmId)?.let { "FILM:${it.id}" } ?: preset
    val activeLook: LookSettings get() = looks[activeLookKey]
        ?: FilmRegistry.find(filmId)?.let { LookSettings.fromRecipe(it.defaults) }
        ?: LookSettings()

    fun updateLook(transform: (LookSettings) -> LookSettings) =
        copy(looks = looks + (activeLookKey to transform(activeLook)))
    fun resetLook() = copy(looks = looks - activeLookKey)

    fun colorRecipe(): ColorRecipe = activeLook.let { look ->
        ColorRecipe(
            preset = FilmRegistry.find(filmId)?.basePreset
                ?: runCatching { ColorPreset.valueOf(preset) }.getOrDefault(ColorPreset.ORIGINAL),
            intensity = look.intensity, grain = look.grain, vignette = look.vignette,
            saturation = look.saturation, hue = look.hue, contrast = look.contrast,
            brightness = look.brightness, highlights = look.highlights, shadows = look.shadows,
            sharpness = look.sharpness, clarity = look.clarity,
            hdfEnabled = hdfEnabled, hdfStrength = hdfStrength,
            hdrEnabled = hdrEnabled, hdrStrength = hdrStrength,
            whiteBalanceAmber = whiteBalanceAmber, whiteBalanceGreen = whiteBalanceGreen,
        )
    }
}

/** Stored IDs are independent of the Compose catalog so loading settings never needs UI code. */
internal object CameraPreferences {
    const val DEFAULT_ORIENTATION = "AUTO"

    /** Explicitly hides the viewfinder nameplate. */
    const val HIDDEN = "HIDDEN"
    private val mascots = setOf("RICOH_GR", "FUJIFILM_X100VI", "SIGMA_FP", "PANASONIC_LX100", "NIKON_F3")
    private val orientations = setOf("AUTO", "LANDSCAPE", "PORTRAIT")

    /**
     * null = follow the active look/film family; [HIDDEN] = no nameplate; a known camera
     * id = that pinned body. Legacy docs that pinned a body keep it; unknown ids recover
     * to follow-look. (The pre-T19 explicit "hidden" value was JSON null, which now means
     * follow-look — a deliberate, documented migration.)
     */
    fun nameplate(id: String?): String? = when {
        id == null || id == HIDDEN -> id
        id in mascots -> id
        else -> null
    }
    fun orientation(id: String?): String = id?.takeIf { it in orientations } ?: DEFAULT_ORIENTATION
}

/** Viewfinder overlay cycle. Nothing here is fabricated: the readout shows reported camera values only. */
internal object ViewfinderOsd {
    const val OFF = "OFF"
    const val HISTOGRAM = "HISTOGRAM"
    const val PARAMS = "PARAMS"
    private val modes = setOf(OFF, HISTOGRAM, PARAMS)
    fun mode(value: String?): String = value?.takeIf { it in modes } ?: OFF
    fun next(value: String?): String = when (mode(value)) {
        OFF -> HISTOGRAM
        HISTOGRAM -> PARAMS
        else -> OFF
    }
}

/** Keep the old key so installing over v0.1 migrates settings without losing the selected look. */
class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("recipe_v1")
    val settings: Flow<AppSettings> = context.recipeStore.data
        .catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw exception
        }
        .map { preferences -> SettingsJson.decode(preferences[key]) }

    suspend fun save(settings: AppSettings) {
        context.recipeStore.edit { it[key] = SettingsJson.encode(settings) }
    }
}

internal object SettingsJson {
    fun encode(settings: AppSettings): String = JSONObject().apply {
        put("version", 4)
        put("focalLength", settings.focalLength)
        put("exposure", settings.exposure)
        put("focus", settings.focus)
        put("focusDiopters", settings.focusDiopters.toDouble())
        put("iso", settings.iso ?: JSONObject.NULL)
        put("exposureTimeNs", settings.exposureTimeNs ?: JSONObject.NULL)
        put("grid", settings.grid)
        put("level", settings.level)
        put("viewfinderOsd", ViewfinderOsd.mode(settings.viewfinderOsd))
        put("preset", settings.preset)
        put("filmId", settings.filmId ?: JSONObject.NULL)
        put("mascotCamera", CameraPreferences.nameplate(settings.mascotCamera) ?: JSONObject.NULL)
        put("captureOrientation", CameraPreferences.orientation(settings.captureOrientation))
        put("compositionEnabled", settings.compositionEnabled)
        put("compositionRealtime", settings.compositionRealtime)
        put("compositionModel", EdgeModelCatalog.find(settings.compositionModel)?.id ?: EdgeModelCatalog.DEFAULT_MODEL_ID)
        put("lutName", settings.lutName ?: JSONObject.NULL)
        put("looks", JSONObject().apply {
            settings.looks.forEach { (preset, look) ->
                put(preset, JSONObject().apply {
                    put("intensity", look.intensity.toDouble())
                    put("grain", look.grain.toDouble())
                    put("vignette", look.vignette.toDouble())
                    put("saturation", look.saturation.toDouble())
                    put("hue", look.hue.toDouble())
                    put("contrast", look.contrast.toDouble())
                    put("brightness", look.brightness.toDouble())
                    put("highlights", look.highlights.toDouble())
                    put("shadows", look.shadows.toDouble())
                    put("sharpness", look.sharpness.toDouble())
                    put("clarity", look.clarity.toDouble())
                })
            }
        })
        put("hdfEnabled", settings.hdfEnabled)
        put("hdfStrength", settings.hdfStrength.toDouble())
        put("hdrEnabled", settings.hdrEnabled)
        put("hdrStrength", settings.hdrStrength.toDouble())
        put("whiteBalance", settings.whiteBalance)
        put("whiteBalanceAmber", settings.whiteBalanceAmber)
        put("whiteBalanceGreen", settings.whiteBalanceGreen)
        put("lookAdviceEnabled", settings.lookAdviceEnabled)
        put("exposureAdviceEnabled", settings.exposureAdviceEnabled)
        put("alignmentHapticEnabled", settings.alignmentHapticEnabled)
        put("lookDismissedAt", JSONObject().apply {
            settings.lookDismissedAt.forEach { (lookKey, at) -> put(lookKey, at) }
        })
        put("lookAdviceDismissStreak", settings.lookAdviceDismissStreak)
    }.toString()

    fun decode(value: String?): AppSettings {
        if (value == null) return AppSettings()
        return runCatching {
            val json = JSONObject(value)
            val preset = json.optString("preset", "ORIGINAL")
                .takeIf { name -> ColorPreset.entries.any { it.name == name } } ?: "ORIGINAL"
            val savedLooks = json.optJSONObject("looks")
            val looks = if (savedLooks != null) {
                val presetLooks = ColorPreset.entries.mapNotNull { choice ->
                    savedLooks.optJSONObject(choice.name)?.let { choice.name to decodeLook(it) }
                }
                val filmLooks = FilmRegistry.entries.mapNotNull { film ->
                    val key = "FILM:${film.id}"
                    savedLooks.optJSONObject(key)?.let {
                        key to decodeLook(it, LookSettings.fromRecipe(film.defaults))
                    }
                }
                (presetLooks + filmLooks).toMap()
            } else if (json.optInt("version", 1) == 1) {
                mapOf(preset to decodeLook(json))
            } else emptyMap()
            AppSettings(
                focalLength = json.optInt("focalLength", 28).takeIf { it in listOf(0, 28, 35, 50) } ?: 28,
                exposure = json.optInt("exposure", 0),
                focus = json.optString("focus", "AUTO").takeIf { it == "MANUAL" } ?: "AUTO",
                focusDiopters = json.amount("focusDiopters", 0f, 0f, 100f),
                iso = if (json.isNull("iso")) null else json.optInt("iso").takeIf { it > 0 },
                exposureTimeNs = if (json.isNull("exposureTimeNs")) null else json.optLong("exposureTimeNs").takeIf { it > 0L },
                grid = json.optBoolean("grid", true),
                level = json.optBoolean("level", true),
                viewfinderOsd = ViewfinderOsd.mode(json.optString("viewfinderOsd").takeIf { it.isNotEmpty() }),
                preset = preset,
                filmId = if (json.isNull("filmId")) null else json.optString("filmId")
                    .takeIf { FilmRegistry.find(it) != null },
                mascotCamera = if (!json.has("mascotCamera") || json.isNull("mascotCamera")) null
                    else CameraPreferences.nameplate(json.optString("mascotCamera")),
                captureOrientation = CameraPreferences.orientation(json.optString("captureOrientation")),
                compositionEnabled = json.optBoolean("compositionEnabled", false),
                compositionRealtime = json.optBoolean("compositionRealtime", false),
                compositionModel = EdgeModelCatalog.find(json.optString("compositionModel"))?.id ?: EdgeModelCatalog.DEFAULT_MODEL_ID,
                looks = looks,
                lutName = if (json.isNull("lutName")) null else json.optString("lutName").takeIf(String::isNotBlank)?.take(120),
                hdfEnabled = json.optBoolean("hdfEnabled", false),
                hdfStrength = json.amount("hdfStrength", .4f),
                hdrEnabled = json.optBoolean("hdrEnabled", false),
                hdrStrength = json.amount("hdrStrength", .6f),
                whiteBalance = json.optString("whiteBalance", "AUTO")
                    .takeIf { name -> WhiteBalanceMode.entries.any { it.name == name } } ?: "AUTO",
                whiteBalanceAmber = json.optInt("whiteBalanceAmber", 0).coerceIn(-9, 9),
                whiteBalanceGreen = json.optInt("whiteBalanceGreen", 0).coerceIn(-9, 9),
                lookAdviceEnabled = json.optBoolean("lookAdviceEnabled", true),
                exposureAdviceEnabled = json.optBoolean("exposureAdviceEnabled", true),
                alignmentHapticEnabled = json.optBoolean("alignmentHapticEnabled", false),
                lookDismissedAt = json.optJSONObject("lookDismissedAt")?.let { dismissed ->
                    dismissed.keys().asSequence()
                        .mapNotNull { lookKey ->
                            val at = dismissed.optLong(lookKey, 0L)
                            lookKey.takeIf { it.length <= 64 }?.let { key -> if (at > 0L) key to at else null }
                        }
                        .toMap()
                } ?: emptyMap(),
                lookAdviceDismissStreak = json.optInt("lookAdviceDismissStreak", 0).coerceIn(0, 3),
            )
        }.getOrDefault(AppSettings())
    }

    private fun decodeLook(json: JSONObject, defaults: LookSettings = LookSettings()) = LookSettings(
        intensity = json.amount("intensity", defaults.intensity),
        grain = json.amount("grain", defaults.grain), vignette = json.amount("vignette", defaults.vignette),
        saturation = json.amount("saturation", defaults.saturation, -1f), hue = json.amount("hue", defaults.hue, -1f),
        contrast = json.amount("contrast", defaults.contrast, -1f), brightness = json.amount("brightness", defaults.brightness, -1f),
        highlights = json.amount("highlights", defaults.highlights, -1f), shadows = json.amount("shadows", defaults.shadows, -1f),
        sharpness = json.amount("sharpness", defaults.sharpness, -1f), clarity = json.amount("clarity", defaults.clarity, -1f),
    )

    private fun JSONObject.amount(name: String, default: Float, minimum: Float = 0f, maximum: Float = 1f): Float =
        optDouble(name, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(minimum, maximum) ?: default
}
