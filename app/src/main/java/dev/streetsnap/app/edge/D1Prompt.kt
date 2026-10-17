package dev.streetsnap.app.edge

/**
 * Direct port of Liquid d1's official `prompt.py` question encoding.
 *
 * Sequence:
 *   <bos> <reserved_7 state> state <reserved_8 q> instructions
 *         <reserved_9 opt> <mask> option_0 <reserved_10> ... <reserved_11 decide>
 *
 * The model scores the hidden state at every `<mask>`; returned marker positions are
 * relative to the text sequence (the ONNX decision graph adds the vision prefix itself).
 */
internal object D1Prompt {
    const val QTYPE_CHOICE = 0L
    const val QTYPE_SCORE = 1L
    const val QTYPE_NOUL = 2L

    /** Image questions use [imageTextLength] text positions on top of the 256 vision prefix. */
    const val IMAGE_TEXT_LENGTH = 896

    private val SPECIAL_LIKE = Regex("<\\|([A-Za-z0-9_]+)\\|>")

    /** Caller text can never forge a delimiter or marker token. */
    fun escape(text: String): String = SPECIAL_LIKE.replace(text) { match ->
        "<¦${match.groupValues[1]}¦>"
    }

    data class Encoded(
        val ids: LongArray,
        val markerPositions: LongArray,
    ) {
        val optionCount: Int get() = markerPositions.size
    }

    /**
     * @param encode tokenizer for plain (already escaped) text without added specials
     * @param idOf reserved/mask/bos token id lookup
     * @param options rendered option texts, e.g. "a: crop option A"
     */
    fun encode(
        encode: (String) -> LongArray,
        idOf: (String) -> Int,
        state: String,
        instructions: String,
        options: List<String>,
        maxLen: Int = IMAGE_TEXT_LENGTH,
        perOption: Int = 24,
    ): Encoded {
        require(options.size in 2..10) { "d1 choice needs 2 to 10 options" }
        val bos = idOf("<bos>").toLong()
        fun ids(token: String): Long = idOf(token).toLong()

        val budget = maxOf(96, minOf(options.size * perOption + 32, maxLen / 2))
        val per = maxOf(2, (budget - 3 * options.size) / options.size)
        val question = ArrayList<Long>()
        question += ids("<|reserved_8|>")
        question += encode(escape(instructions)).asIterable().take(maxOf(16, budget) - 1)

        val markers = ArrayList<Long>()
        for (text in options) {
            markers += (question.size + 1).toLong()
            question += ids("<|reserved_9|>")
            question += ids("<|mask|>")
            question += encode(escape(" $text")).asIterable().take(per)
            question += ids("<|reserved_10|>")
        }
        question += ids("<|reserved_11|>")

        val room = maxOf(0, maxLen - question.size - 2)
        val stateIds = ArrayList<Long>()
        stateIds += ids("<|reserved_7|>")
        stateIds += encode(escape(state)).asIterable().take(room)

        val all = ArrayList<Long>(stateIds.size + question.size + 1)
        all += bos
        all += stateIds
        all += question
        val ids = if (all.size > maxLen) LongArray(maxLen) { all[it] } else all.toLongArray()

        val offset = 1 + stateIds.size
        val markerPositions = LongArray(markers.size) { markers[it] + offset }
        require(markerPositions.last() < maxLen) { "d1 options do not fit in the context" }
        require(markerPositions.all { it < ids.size }) { "d1 marker lies outside the encoded sequence" }
        return Encoded(ids, markerPositions)
    }

    /** The three contact-sheet choices, matching CandidateContactSheet's A/B/C labels. */
    val CANDIDATE_OPTIONS = listOf("a: crop option A", "b: crop option B", "c: crop option C")
    const val CANDIDATE_QUESTION = "Which crop gives the best composition?"
}
