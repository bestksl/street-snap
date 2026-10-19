package dev.streetsnap.app.edge

import java.io.File

/** Weights are optional, fetched only after the user explicitly starts a download. */
data class EdgeModelEntry(
    val id: String,
    val title: String,
    val repository: String,
    val revision: String,
) {
    val sourceUrl: String get() = "https://huggingface.co/$repository"
}

object EdgeModelCatalog {
    /** The ranker a fresh install selects and unknown/legacy ids (including the retired SmolVLM picks) fall back to. */
    const val DEFAULT_MODEL_ID = "d1-omni-600m"

    // The two SmolVLM generative rankers were retired on 2026-10-20: the non-autoregressive
    // d1 decision head is markedly faster on-device and more stable on the marker contract.
    // Legacy persisted ids migrate to the default via SettingsJson's find(...) ?: DEFAULT.
    val models = listOf(
        EdgeModelEntry(
            "d1-omni-600m", "d1-omni 600M", "onnx-community/d1-omni-600M-ONNX",
            "4ebc1b97bf1477485371c79d1cf8d5e4e8eebfa0",
        ),
    )
    fun find(id: String?): EdgeModelEntry? = models.firstOrNull { it.id == id }
}

data class ModelFile(
    val path: String,
    val bytes: Long,
    val digest: String,
    val isLfsSha256: Boolean,
)

data class ModelDownloadPlan(
    val model: EdgeModelEntry,
    val revision: String,
    val files: List<ModelFile>,
    val visionFile: String,
    val embeddingFile: String,
    val decoderFile: String,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

data class ModelDownloadProgress(val downloadedBytes: Long, val totalBytes: Long, val file: String)
data class InstalledEdgeModel(val plan: ModelDownloadPlan, val directory: File)

/** Coordinates refer to the complete input frame, before the UI applies its display transform. */
data class CompositionSuggestion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val modelText: String,
    /**
     * Rule-layer executable move (T33), shown above the terminology line. Always null for
     * model-produced suggestions: [parse] never sets it, so untrusted model text cannot.
     */
    val actionText: String? = null,
) {
    companion object {
        // Model text is untrusted data. Accept one bounded numeric rectangle, never instructions.
        private val rectangle = Regex("\\[\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*,\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*,\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*,\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*]")
        fun parse(text: String): CompositionSuggestion? {
            val matches = rectangle.findAll(text.take(2_048)).toList()
            if (matches.size != 1) return null
            val values = matches.single().groupValues.drop(1).map { it.toFloatOrNull() ?: return null }
            if (values.any { !it.isFinite() || it !in 0f..1000f }) return null
            val (x0, y0, x1, y1) = values
            if (x1 - x0 < 100f || y1 - y0 < 100f) return null
            return CompositionSuggestion(x0 / 1000f, y0 / 1000f, x1 / 1000f, y1 / 1000f, text.take(2_048))
        }
    }
}
