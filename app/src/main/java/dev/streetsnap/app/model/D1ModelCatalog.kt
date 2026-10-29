package dev.streetsnap.app.model

import java.io.File

/**
 * d1-omni 构图决策模型的目录条目。权重永远可选：仅在用户显式发起下载后获取。
 */
data class D1ModelEntry(
    val id: String,
    val title: String,
    val repository: String,
    val revision: String,
) {
    val sourceUrl: String get() = "https://huggingface.co/$repository"
}

/** d1-omni 多文件权重包目录。当前只有一个在线档位，历史 id 由设置层回落到默认项。 */
object D1ModelCatalog {
    /** 全新安装的默认排序模型；未知/历史 id（含已下架的 SmolVLM 选项）也回落到它。 */
    const val DEFAULT_MODEL_ID = "d1-omni-600m"

    // 两个 SmolVLM 生成式排序模型已于 2026-10-20 下架：d1 非自回归决策头在端上明显更快，
    // 标记契约也更稳定。历史设置 id 通过 SettingsJson 的 find(...) ?: DEFAULT 迁移。
    val models = listOf(
        D1ModelEntry(
            "d1-omni-600m", "d1-omni 600M", "onnx-community/d1-omni-600M-ONNX",
            "4ebc1b97bf1477485371c79d1cf8d5e4e8eebfa0",
        ),
    )

    fun find(id: String?): D1ModelEntry? = models.firstOrNull { it.id == id }
}

/**
 * 一个 d1 权重包的完整下载/安装计划：固定的三张 ONNX 图（视觉塔 / embedding / 决策头）
 * 加配置、分词器与许可证。可由 APK 内置的固定清单离线产生，也可由在线目录兜底产生。
 */
data class ModelDownloadPlan(
    val model: D1ModelEntry,
    val revision: String,
    val files: List<WeightFile>,
    val visionFile: String,
    val embeddingFile: String,
    val decoderFile: String,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

/** 下载进度：整个权重包的累计字节与当前文件名。 */
data class ModelDownloadProgress(val downloadedBytes: Long, val totalBytes: Long, val file: String)

/** 已落盘并通过校验的 d1 权重包；[directory] 下持有全部计划文件与 install.json 就绪标记。 */
data class InstalledD1Model(val plan: ModelDownloadPlan, val directory: File)
