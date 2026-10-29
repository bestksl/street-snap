package dev.streetsnap.app.model

/**
 * T72 可选的端侧姿势模型。MediaPipe Tasks Vision 运行时是构建期依赖（Google dl.google.com
 * Maven，与 CameraX 同源）；权重绝不打包：.task 包仅在用户于应用内显式发起下载后获取。
 *
 * 下载地址统一由 [ModelSources] 给出（魔搭国内 CDN 优先，Google Storage 兜底），
 * 并与固定 SHA-256 逐字节校验，任一源都只能返回这一个文件。
 *
 * v1 仅开放 lite 档（5.8 MB fp16）。full（9.4 MB）与 heavy（30.7 MB）仍只记录在
 * docs/pose-assistant-plan.md，待真机验证证明必要后再开放。
 */
data class PoseModelEntry(
    val id: String,
    val title: String,
    val bytes: Long,
    val sha256: String,
) {
    /** 有序匿名下载源；摘要 pin 死，任何源都安全。 */
    val downloadUrls: List<String> get() = ModelSources.poseTask()

    /** 单文件权重描述：落盘固定名 model.task。 */
    val weight: WeightFile
        get() = WeightFile(path = "model.task", bytes = bytes, digest = sha256, isSha256 = true)
}

object PoseModelCatalog {
    const val DEFAULT_ID = "pose_landmarker_lite"

    val lite = PoseModelEntry(
        id = DEFAULT_ID,
        title = "MediaPipe PoseLandmarker lite（fp16）",
        bytes = 5_777_746L,
        sha256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a",
    )

    val models = listOf(lite)

    fun find(id: String?): PoseModelEntry? = models.firstOrNull { it.id == id }
}
