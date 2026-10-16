package dev.streetsnap.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.streetsnap.camera.WhiteBalanceMode
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.ColorRecipe
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
)

data class AppSettings(
    val focalLength: Int = 28,
    val exposure: Int = 0,
    val focus: String = "AUTO",
    val focusDiopters: Float = 0f,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val grid: Boolean = true,
    val level: Boolean = true,
    val preset: String = "ORIGINAL",
    val looks: Map<String, LookSettings> = emptyMap(),
    val lutName: String? = null,
    val hdfEnabled: Boolean = false,
    val hdfStrength: Float = .4f,
    val whiteBalance: String = "AUTO",
    val whiteBalanceAmber: Int = 0,
    val whiteBalanceGreen: Int = 0,
) {
    val activeLook: LookSettings get() = looks[preset] ?: LookSettings()

    fun updateLook(transform: (LookSettings) -> LookSettings) = copy(looks = looks + (preset to transform(activeLook)))
    fun resetLook() = copy(looks = looks - preset)

    fun colorRecipe(): ColorRecipe = activeLook.let { look ->
        ColorRecipe(
            preset = runCatching { ColorPreset.valueOf(preset) }.getOrDefault(ColorPreset.ORIGINAL),
            intensity = look.intensity, grain = look.grain, vignette = look.vignette,
            saturation = look.saturation, hue = look.hue, contrast = look.contrast,
            brightness = look.brightness, highlights = look.highlights, shadows = look.shadows,
            sharpness = look.sharpness, clarity = look.clarity,
            hdfEnabled = hdfEnabled, hdfStrength = hdfStrength,
            whiteBalanceAmber = whiteBalanceAmber, whiteBalanceGreen = whiteBalanceGreen,
        )
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
        put("version", 2)
        put("focalLength", settings.focalLength)
        put("exposure", settings.exposure)
        put("focus", settings.focus)
        put("focusDiopters", settings.focusDiopters.toDouble())
        put("iso", settings.iso ?: JSONObject.NULL)
        put("exposureTimeNs", settings.exposureTimeNs ?: JSONObject.NULL)
        put("grid", settings.grid)
        put("level", settings.level)
        put("preset", settings.preset)
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
        put("whiteBalance", settings.whiteBalance)
        put("whiteBalanceAmber", settings.whiteBalanceAmber)
        put("whiteBalanceGreen", settings.whiteBalanceGreen)
    }.toString()

    fun decode(value: String?): AppSettings {
        if (value == null) return AppSettings()
        return runCatching {
            val json = JSONObject(value)
            val preset = json.optString("preset", "ORIGINAL")
                .takeIf { name -> ColorPreset.entries.any { it.name == name } } ?: "ORIGINAL"
            val savedLooks = json.optJSONObject("looks")
            val looks = if (savedLooks != null) {
                ColorPreset.entries.mapNotNull { choice ->
                    savedLooks.optJSONObject(choice.name)?.let { choice.name to decodeLook(it) }
                }.toMap()
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
                preset = preset,
                looks = looks,
                lutName = if (json.isNull("lutName")) null else json.optString("lutName").takeIf(String::isNotBlank)?.take(120),
                hdfEnabled = json.optBoolean("hdfEnabled", false),
                hdfStrength = json.amount("hdfStrength", .4f),
                whiteBalance = json.optString("whiteBalance", "AUTO")
                    .takeIf { name -> WhiteBalanceMode.entries.any { it.name == name } } ?: "AUTO",
                whiteBalanceAmber = json.optInt("whiteBalanceAmber", 0).coerceIn(-9, 9),
                whiteBalanceGreen = json.optInt("whiteBalanceGreen", 0).coerceIn(-9, 9),
            )
        }.getOrDefault(AppSettings())
    }

    private fun decodeLook(json: JSONObject) = LookSettings(
        intensity = json.amount("intensity", 1f), grain = json.amount("grain", 0f), vignette = json.amount("vignette", 0f),
        saturation = json.amount("saturation", 0f, -1f), hue = json.amount("hue", 0f, -1f),
        contrast = json.amount("contrast", 0f, -1f), brightness = json.amount("brightness", 0f, -1f),
        highlights = json.amount("highlights", 0f, -1f), shadows = json.amount("shadows", 0f, -1f),
        sharpness = json.amount("sharpness", 0f, -1f), clarity = json.amount("clarity", 0f, -1f),
    )

    private fun JSONObject.amount(name: String, default: Float, minimum: Float = 0f, maximum: Float = 1f): Float =
        optDouble(name, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(minimum, maximum) ?: default
}
