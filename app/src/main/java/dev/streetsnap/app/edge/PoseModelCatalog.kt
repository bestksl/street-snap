package dev.streetsnap.app.edge

/**
 * T72 optional on-device pose model. The MediaPipe Tasks Vision runtime is a build-time
 * dependency (Google's dl.google.com Maven, same source as CameraX); the weights are
 * never packaged: the .task bundle is fetched from Google's pinned mediapipe-models
 * storage only after an explicit in-app download action.
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
)

object PoseModelCatalog {
    const val DEFAULT_ID = "pose_landmarker_lite"

    val lite = PoseModelEntry(
        id = DEFAULT_ID,
        title = "MediaPipe PoseLandmarker lite（fp16）",
        url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
        bytes = 5_777_746L,
        sha256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a",
    )

    val models = listOf(lite)

    fun find(id: String?): PoseModelEntry? = models.firstOrNull { it.id == id }
}
