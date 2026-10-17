package dev.streetsnap.app.edge

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteBpeTokenizerTest {
    @Test fun digitsStagePreventsMergesAcrossNumericBoundaries() = withTokenizer(true) { tokenizer ->
        // Even when the vocabulary can merge a space with a digit or two digits,
        // the official Digits -> ByteLevel sequence forbids both merges.
        assertArrayEquals(longArrayOf(0, 1, 2, 2, 0, 7, 8), tokenizer.encode(" 100 ab\n"))
    }

    @Test fun plainByteLevelStillUsesAvailableNumericMerges() = withTokenizer(false) { tokenizer ->
        assertArrayEquals(longArrayOf(3, 4, 0, 7, 8), tokenizer.encode(" 100 ab\n"))
    }

    @Test fun imageMarkersRemainSingleTokensWithoutAnExtraBos() = withTokenizer(true) { tokenizer ->
        val encoded = tokenizer.encode("<image>100<|im_start|>")
        assertArrayEquals(longArrayOf(100, 1, 2, 2, 101), encoded)
        assertEquals("100", tokenizer.decode(encoded.map(Long::toInt)))
        assertEquals(100, tokenizer.specialId("<image>"))
    }

    private fun withTokenizer(individualDigits: Boolean, test: (ByteBpeTokenizer) -> Unit) {
        val byteLevel = JSONObject().put("type", "ByteLevel").put("add_prefix_space", false).put("use_regex", true)
        val stages = JSONArray()
        if (individualDigits) stages.put(JSONObject().put("type", "Digits").put("individual_digits", true))
        stages.put(byteLevel)
        val vocab = JSONObject(mapOf("Ġ" to 0, "1" to 1, "0" to 2, "Ġ1" to 3, "00" to 4,
            "a" to 5, "b" to 6, "ab" to 7, "Ċ" to 8))
        val added = JSONArray()
        listOf("<image>" to 100, "<|im_start|>" to 101).forEach { (content, id) ->
            added.put(JSONObject().put("content", content).put("id", id).put("special", true))
        }
        val json = JSONObject()
            .put("model", JSONObject().put("type", "BPE").put("vocab", vocab)
                .put("merges", JSONArray(listOf("Ġ 1", "0 0", "a b"))))
            .put("added_tokens", added)
            .put("pre_tokenizer", JSONObject().put("type", "Sequence").put("pretokenizers", stages))
        val file = File.createTempFile("street-snap-tokenizer-", ".json")
        try {
            file.writeText(json.toString())
            test(ByteBpeTokenizer(file))
        } finally { file.delete() }
    }
}
