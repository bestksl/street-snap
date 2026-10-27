package dev.streetsnap.app.edge

/**
 * T72 optional on-device pose model. The MediaPipe Tasks Vision runtime is a build-time
 * dependency (Google's dl.google.com Maven, same source as CameraX); the weights are
 * never packaged: the .task bundle is fetched only after an explicit in-app download
 * action. Google Storage is primary; the anonymous ModelScope CN CDN mirror (file pinned
 * at an immutable commit revision) is the no-VPN fallback. Both are verified against the
 * same pinned SHA-256, so either source can only serve exactly this file.
 *
 * v1 ships the lite tier only (5.8 MB fp16). Full (9.4 MB) and heavy (30.7 MB) stay
 * documented options in docs/pose-assistant-plan.md until real-device testing justifies
 * exposing them.
 */
data class PoseModelEntry(
    val id: String,
    val title: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    /** Extra anonymous, no-auth mirrors tried after [url]. The pinned SHA-256 makes any source safe. */
    val mirrorUrls: List<String> = emptyList(),
) {
    val downloadUrls: List<String> get() = listOf(url) + mirrorUrls
}

object PoseModelCatalog {
    const val DEFAULT_ID = "pose_landmarker_lite"

    val lite = PoseModelEntry(
        id = DEFAULT_ID,
        title = "MediaPipe PoseLandmarker lite（fp16）",
        url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
        bytes = 5_777_746L,
        sha256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a",
        // 魔搭社区匿名镜像（国内 CDN，无需登录/token），固定到上传该文件的 commit
        // 而非 master；2026-10-28 凌晨国内直连实测 Range 206、全量哈希一致。
        mirrorUrls = listOf(
            "https://modelscope.cn/api/v1/models/Composable/pose_landmarker_lite/repo" +
                "?Revision=b952ec12202f43e88a690e074cc66d86d0d53700&FilePath=pose_landmarker_lite.task",
        ),
    )

    val models = listOf(lite)

    fun find(id: String?): PoseModelEntry? = models.firstOrNull { it.id == id }
}
