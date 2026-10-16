package dev.streetsnap.color

import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/** SDR .cube 3D LUT. Red changes fastest, then green, then blue (the .cube convention). */
class CubeLut private constructor(
    val title: String,
    val size: Int,
    private val values: FloatArray,
    private val minimum: FloatArray,
    private val maximum: FloatArray,
) {
    val domainMin: FloatArray get() = minimum.copyOf()
    val domainMax: FloatArray get() = maximum.copyOf()

    /** Trilinear CPU reference, also used to verify LUT ordering independently of GL. */
    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        require(red.isFinite() && green.isFinite() && blue.isFinite())
        val rgb = floatArrayOf(red, green, blue)
        val lower = IntArray(3)
        val upper = IntArray(3)
        val weight = FloatArray(3)
        for (c in 0..2) {
            val position = ((rgb[c] - minimum[c]) / (maximum[c] - minimum[c]))
                .coerceIn(0f, 1f) * (size - 1)
            lower[c] = floor(position).toInt()
            upper[c] = (lower[c] + 1).coerceAtMost(size - 1)
            weight[c] = position - lower[c]
        }
        return FloatArray(3) { channel ->
            var result = 0f
            for (z in 0..1) for (y in 0..1) for (x in 0..1) {
                val r = if (x == 0) lower[0] else upper[0]
                val g = if (y == 0) lower[1] else upper[1]
                val b = if (z == 0) lower[2] else upper[2]
                val factor = (if (x == 0) 1f - weight[0] else weight[0]) *
                    (if (y == 0) 1f - weight[1] else weight[1]) *
                    (if (z == 0) 1f - weight[2] else weight[2])
                result += values[((b * size + g) * size + r) * 3 + channel] * factor
            }
            result
        }
    }

    /** RGBA floats for GL_RGBA16F upload; the original immutable LUT stays on the CPU. */
    fun toRgbaBuffer(): FloatBuffer = ByteBuffer.allocateDirect(size * size * size * 16)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            for (i in values.indices step 3) {
                put(values[i]); put(values[i + 1]); put(values[i + 2]); put(1f)
            }
            flip()
        }

    companion object {
        const val MAX_FILE_CHARACTERS = 4 * 1024 * 1024
        private const val MAX_LINE_CHARACTERS = 4096

        /** Accepts one 17³ or 33³ SDR table. 1D, combined tables and HDR values are rejected. */
        fun parse(reader: Reader): CubeLut {
            var title = "自定义"
            var size: Int? = null
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            var minSeen = false
            var maxSeen = false
            var titleSeen = false
            var dataStarted = false
            var rows = 0
            var data: FloatArray? = null
            var consumed = 0
            var lineNumber = 0
            val line = StringBuilder()

            fun fail(message: String): Nothing =
                throw IllegalArgumentException("LUT 第 $lineNumber 行：$message")

            fun triple(tokens: List<String>): FloatArray {
                if (tokens.size != 3) fail("需要 3 个通道数值")
                return FloatArray(3) { index ->
                    tokens[index].toFloatOrNull()?.takeIf { it.isFinite() }
                        ?: fail("通道必须为有限数值")
                }
            }

            fun consumeLine() {
                lineNumber++
                // A # inside a quoted TITLE is a title character, not the start of a comment.
                var quoted = false
                var end = line.length
                for (index in line.indices) {
                    if (line[index] == '"') quoted = !quoted
                    if (line[index] == '#' && !quoted) { end = index; break }
                }
                val text = line.substring(0, end).trim()
                line.setLength(0)
                if (text.isEmpty()) return
                val tokens = text.split(Regex("\\s+"))
                when (tokens[0]) {
                    "TITLE" -> {
                        if (titleSeen || dataStarted) fail("TITLE 重复或顺序错误")
                        val titleText = text.removePrefix("TITLE").trim()
                        if (titleText.length < 2 || !titleText.startsWith('"') || !titleText.endsWith('"'))
                            fail("TITLE 需要双引号")
                        title = titleText.substring(1, titleText.lastIndex).take(120)
                        titleSeen = true
                    }
                    "LUT_3D_SIZE" -> {
                        if (size != null || dataStarted || tokens.size != 2) fail("LUT_3D_SIZE 重复或格式错误")
                        size = tokens[1].toIntOrNull()?.takeIf { it == 17 || it == 33 }
                            ?: fail("只支持 17³ / 33³ SDR LUT")
                        data = FloatArray(size!! * size!! * size!! * 3)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        if (dataStarted) fail("DOMAIN 须位于色表之前")
                        val domain = triple(tokens.drop(1))
                        if (tokens[0] == "DOMAIN_MIN") {
                            if (minSeen) fail("DOMAIN_MIN 重复")
                            min = domain; minSeen = true
                        } else {
                            if (maxSeen) fail("DOMAIN_MAX 重复")
                            max = domain; maxSeen = true
                        }
                    }
                    "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE" ->
                        fail("不支持 1D、混合色表或扩展输入格式")
                    else -> {
                        val current = data ?: fail("色表数据之前须声明 LUT_3D_SIZE")
                        val rgb = triple(tokens)
                        if (rgb.any { it !in 0f..1f }) fail("仅支持通道值在 0–1 之间的 SDR LUT")
                        if (rows >= current.size / 3) fail("色表行数超过声明大小")
                        rgb.copyInto(current, rows * 3)
                        rows++
                        dataStarted = true
                    }
                }
            }

            val chunk = CharArray(8192)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                consumed += count
                require(consumed <= MAX_FILE_CHARACTERS) { "LUT 文件不能超过 4 MiB 字符" }
                for (i in 0 until count) {
                    val char = chunk[i]
                    if (char == '\n') consumeLine()
                    else {
                        if (char != '\r' && !(consumed == count && i == 0 && char == '\uFEFF')) line.append(char)
                        require(line.length <= MAX_LINE_CHARACTERS) { "LUT 单行过长" }
                    }
                }
            }
            if (line.isNotEmpty()) consumeLine()
            val actualSize = size ?: throw IllegalArgumentException("缺少 LUT_3D_SIZE")
            require(rows == actualSize * actualSize * actualSize) { "LUT 色表行数不完整：$rows" }
            require((0..2).all { min[it] < max[it] && (max[it] - min[it]).isFinite() }) {
                "DOMAIN_MAX 必须逐通道大于 DOMAIN_MIN，且范围为有限数值"
            }
            return CubeLut(title, actualSize, data!!, min, max)
        }

        fun identity(size: Int = 17): CubeLut = generate("原色", size) { r, g, b -> floatArrayOf(r, g, b) }

        internal fun generate(title: String, size: Int = 17, transform: (Float, Float, Float) -> FloatArray): CubeLut {
            require(size == 17 || size == 33)
            val values = FloatArray(size * size * size * 3)
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val rgb = transform(r.toFloat() / (size - 1), g.toFloat() / (size - 1), b.toFloat() / (size - 1))
                require(rgb.size == 3 && rgb.all { it.isFinite() })
                for (c in 0..2) values[((b * size + g) * size + r) * 3 + c] = rgb[c].coerceIn(0f, 1f)
            }
            return CubeLut(title, size, values, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        }
    }
}
