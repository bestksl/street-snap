package dev.streetsnap.app.composition

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
