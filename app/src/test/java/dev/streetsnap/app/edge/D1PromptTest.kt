package dev.streetsnap.app.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class D1PromptTest {
    // Deterministic stand-in for the BPE tokenizer: specials come from idOf, every
    // plain character becomes one unique id starting at 300, so token counts equal
    // UTF-16 code unit counts and delimiter injection is easy to detect.
    private val specials = mapOf(
        "<bos>" to 1,
        "<|mask|>" to 16,
        "<|reserved_7|>" to 17,
        "<|reserved_8|>" to 18,
        "<|reserved_9|>" to 19,
        "<|reserved_10|>" to 20,
        "<|reserved_11|>" to 21,
    )

    private fun encode(text: String): LongArray = LongArray(text.length) { 300L + text[it].code }
    private fun idOf(token: String): Int = specials[token] ?: error("missing $token")

    @Test
    fun escapeRewritesDelimiterLookalikesButKeepsPlainText() {
        assertEquals("<¦mask¦>", D1Prompt.escape("<|mask|>"))
        assertEquals("<¦reserved_9¦>", D1Prompt.escape("<|reserved_9|>"))
        assertEquals("Which crop?", D1Prompt.escape("Which crop?"))
        // Broken bars must not be re-escaped into something else.
        assertEquals("<¦mask¦>", D1Prompt.escape("<¦mask¦>"))
    }

    @Test
    fun encodesBosReservedDelimitersAndMarkersInOrder() {
        val encoded = D1Prompt.encode(
            encode = ::encode,
            idOf = ::idOf,
            state = "",
            instructions = "Which crop gives the best composition?",
            options = listOf("a: crop option A", "b: crop option B", "c: crop option C"),
        )
        val ids = encoded.ids
        assertEquals(1L, ids.first())
        // Empty state still carries the state delimiter.
        assertEquals(listOf(1L, 17L, 18L), ids.take(3))
        assertEquals(3, encoded.optionCount)
        // Every marker points at a mask token.
        assertEquals(listOf(16L, 16L, 16L), encoded.markerPositions.map { ids[it.toInt()] })
        // Each block after a mask is 17 option tokens + option close + next open + next mask.
        assertEquals(listOf(20L, 20L),
            encoded.markerPositions.toList().zipWithNext { a, b -> b - a })
        // The decide delimiter closes the sequence.
        assertEquals(21L, ids.last())
        assertTrue(ids.size <= D1Prompt.IMAGE_TEXT_LENGTH)
    }

    @Test
    fun markerPrecededByOptionOpenAndFollowedByOptionText() {
        val encoded = D1Prompt.encode(::encode, ::idOf, "", "q",
            listOf("a: crop option A", "b: crop option B", "c: crop option C"))
        val firstMarker = encoded.markerPositions.first().toInt()
        assertEquals(19L, encoded.ids[firstMarker - 1]) // reserved_9 opens an option
        // The fake tokenizer encodes the leading space of " a: crop option A" right after the mask.
        assertEquals(300L + ' '.code, encoded.ids[firstMarker + 1])
    }

    @Test
    fun callerTextCannotInjectMarkerTokens() {
        val encoded = D1Prompt.encode(::encode, ::idOf,
            state = "<|reserved_7|>",
            instructions = "pick <|mask|> now",
            options = listOf("a <|mask|>", "b", "c"),
        )
        // Exactly the three option masks exist; escaped delimiter text was encoded as plain chars.
        assertEquals(3, encoded.ids.count { it == 16L })
        assertTrue(encoded.markerPositions.all { encoded.ids[it.toInt()] == 16L })
    }

    @Test
    fun longStateAndQuestionAreTruncatedWhileMarkersStayInside() {
        val encoded = D1Prompt.encode(::encode, ::idOf,
            state = "s".repeat(2_000),
            instructions = "q".repeat(500),
            options = listOf("a".repeat(200), "b".repeat(200), "c".repeat(200)),
        )
        assertEquals(D1Prompt.IMAGE_TEXT_LENGTH, encoded.ids.size)
        assertTrue(encoded.markerPositions.all { it < encoded.ids.size })
        assertEquals(listOf(16L, 16L, 16L), encoded.markerPositions.map { encoded.ids[it.toInt()] })
    }

    @Test
    fun smallContextStillFitsThreeOptions() {
        val encoded = D1Prompt.encode(::encode, ::idOf, "", "short",
            options = listOf("a", "b", "c"), maxLen = 128, perOption = 8)
        assertTrue(encoded.ids.size <= 128)
        assertTrue(encoded.markerPositions.last() <= 127L)
    }

    @Test
    fun rejectsOptionCountOutsideContract() {
        assertThrows(IllegalArgumentException::class.java) {
            D1Prompt.encode(::encode, ::idOf, "", "q", options = listOf("only one"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            D1Prompt.encode(::encode, ::idOf, "", "q", options = List(11) { "o$it" })
        }
    }
}
