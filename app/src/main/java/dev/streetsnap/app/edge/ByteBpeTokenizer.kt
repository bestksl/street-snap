package dev.streetsnap.app.edge

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/** Byte-level BPE for the official SmolLM/SmolVLM tokenizer JSON; unsupported formats fail closed. */
internal class ByteBpeTokenizer(file: File) {
    private val vocabulary: Map<String, Int>
    private val inverse: Map<Int, String>
    private val ranks: Map<Pair<String, String>, Int>
    private val special: Map<String, Int>
    private val specialPattern: Pattern
    private val pieces: Pattern
    private val individualDigits: Boolean
    private val byteToChar: CharArray = byteAlphabet()
    private val charToByte = byteToChar.withIndex().associate { it.value to it.index }
    private val cache = object : LinkedHashMap<String, IntArray>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean = size > 512
    }

    init {
        require(file.length() in 1..8_000_000L) { "Tokenizer 文件大小无效" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val model = json.getJSONObject("model")
        require(model.getString("type") == "BPE" && !model.optBoolean("byte_fallback", false)) { "不支持此 tokenizer 格式" }
        val normalizer = json.opt("normalizer")
        require(normalizer == null || normalizer == JSONObject.NULL) { "Tokenizer normalizer 尚不支持" }
        val vocab = model.getJSONObject("vocab")
        vocabulary = vocab.keys().asSequence().associateWith { vocab.getInt(it) }
        inverse = vocabulary.entries.associate { it.value to it.key }
        val merges = model.getJSONArray("merges")
        ranks = (0 until merges.length()).associate { index ->
            val raw = merges.get(index)
            val pair = if (raw is JSONArray) raw.getString(0) to raw.getString(1)
                else raw.toString().split(' ', limit = 2).let { require(it.size == 2); it[0] to it[1] }
            pair to index
        }
        val added = json.getJSONArray("added_tokens")
        special = (0 until added.length()).associate { index -> added.getJSONObject(index).let {
            require(!it.optBoolean("lstrip") && !it.optBoolean("rstrip")) { "不支持此特殊 token 的空白处理" }
            it.getString("content") to it.getInt("id")
        } }
        specialPattern = Pattern.compile(special.keys.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) })
        val preTokenizer = json.getJSONObject("pre_tokenizer")
        var regex: String? = null
        var byteLevelFound = false
        var splitDigits = false
        fun readPreTokenizer(node: JSONObject) {
            when (node.getString("type")) {
                "Sequence" -> node.getJSONArray("pretokenizers").let { list ->
                    for (i in 0 until list.length()) readPreTokenizer(list.getJSONObject(i))
                }
                "Split" -> {
                    require(node.optString("behavior") == "Isolated" && !node.optBoolean("invert")) { "不支持此 tokenizer split" }
                    regex = node.getJSONObject("pattern").getString("Regex")
                }
                "Digits" -> {
                    // SmolVLM's official tokenizer applies Digits before ByteLevel.
                    // Keep the split explicit so runs such as "256" remain three
                    // byte-level pieces, matching the Rust tokenizer contract.
                    require(node.optBoolean("individual_digits")) { "不支持合并数字预处理" }
                    splitDigits = true
                }
                "ByteLevel" -> {
                    require(!node.optBoolean("add_prefix_space")) { "不支持自动添加前缀空格" }
                    byteLevelFound = true
                    if (regex == null && node.optBoolean("use_regex", true)) {
                        regex = "'s|'t|'re|'ve|'m|'ll|'d| ?[\\p{L}]+| ?[\\p{N}]+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
                    }
                }
                else -> error("不支持的 tokenizer 预处理")
            }
        }
        readPreTokenizer(preTokenizer)
        require(byteLevelFound && regex != null) { "Tokenizer 缺少字节预处理" }
        pieces = Pattern.compile(requireNotNull(regex))
        individualDigits = splitDigits
    }

    fun specialId(token: String): Int = special[token] ?: error("模型缺少 $token")

    fun encode(text: String): LongArray {
        val ids = ArrayList<Long>()
        val matcher = specialPattern.matcher(text)
        var previous = 0
        while (matcher.find()) {
            encodePlain(text.substring(previous, matcher.start()), ids)
            ids += requireNotNull(special[matcher.group()]).toLong()
            previous = matcher.end()
        }
        encodePlain(text.substring(previous), ids)
        return ids.toLongArray()
    }

    private fun encodePlain(text: String, output: MutableList<Long>) {
        if (!individualDigits) {
            encodePieces(text, output)
            return
        }
        // Digits is a separate pre-tokenizer before ByteLevel, so neither a
        // preceding space nor a following digit may merge with a numeric token.
        // Combining both stages into one regex incorrectly allows " 1" merges.
        val digits = DIGITS.matcher(text)
        var previous = 0
        while (digits.find()) {
            encodePieces(text.substring(previous, digits.start()), output)
            encodePieces(digits.group(), output)
            previous = digits.end()
        }
        encodePieces(text.substring(previous), output)
    }

    private fun encodePieces(text: String, output: MutableList<Long>) {
        val matcher = pieces.matcher(text)
        var covered = 0
        while (matcher.find()) {
            require(matcher.start() == covered) { "Tokenizer 无法完整分词" }
            val piece = matcher.group()
            val tokens = cache[piece] ?: bpe(piece).also { cache[piece] = it }
            tokens.forEach { output += it.toLong() }
            covered = matcher.end()
        }
        require(covered == text.length) { "Tokenizer 丢失输入" }
    }

    private fun bpe(text: String): IntArray {
        val segments = text.toByteArray(Charsets.UTF_8).map { byteToChar[it.toInt() and 255].toString() }.toMutableList()
        while (segments.size > 1) {
            var best = Int.MAX_VALUE
            var location = -1
            for (i in 0 until segments.lastIndex) {
                val rank = ranks[segments[i] to segments[i + 1]] ?: continue
                if (rank < best) { best = rank; location = i }
            }
            if (location < 0) break
            segments[location] = segments[location] + segments.removeAt(location + 1)
        }
        return segments.map { vocabulary[it] ?: error("Tokenizer 字典缺少字符") }.toIntArray()
    }

    fun decode(ids: List<Int>): String {
        val output = ByteArrayOutputStream()
        for (id in ids) {
            if (id in special.values) continue
            val token = inverse[id] ?: error("模型返回未知 token")
            for (char in token) output.write(charToByte[char] ?: error("Tokenizer 字节映射无效"))
        }
        return output.toString("UTF-8")
    }

    private fun byteAlphabet(): CharArray {
        val printable = ((33..126) + (161..172) + (174..255)).toSet()
        var extra = 256
        return CharArray(256) { byte -> if (byte in printable) byte.toChar() else (extra++).toChar() }
    }

    companion object {
        private val DIGITS = Pattern.compile("[\\p{N}]")
    }
}
