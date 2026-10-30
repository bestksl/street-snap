package dev.streetsnap.app.model

/**
 * 一个权重文件的不可变描述：仓库内相对路径、精确字节数与内容摘要。
 *
 * 下载源只负责搬运字节；任何源（含镜像）返回的内容都必须与摘要逐字节一致，
 * 因此匿名免鉴权镜像也不可能投毒。
 *
 * @param isSha256 true = LFS 裸 SHA-256；false = git blob SHA-1，计算摘要时需带
 *   `"blob <字节数>\0"` 前缀（与 HuggingFace / ModelScope 的 git 存储一致）。
 */
data class WeightFile(
    val path: String,
    val bytes: Long,
    val digest: String,
    val isSha256: Boolean,
)
