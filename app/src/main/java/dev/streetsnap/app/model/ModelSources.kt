package dev.streetsnap.app.model

/**
 * 唯一的权重下载源优先级表。d1 多文件包与 pose 单文件都从这里取 URL，
 * 全应用不存在第二处拼下载地址的代码。
 *
 * 所有源匿名、免鉴权；每个文件的字节都由 [WeightFile.digest] 钉死，源本身不可信也无妨。
 * 魔搭 ModelScope（阿里云国内 CDN）对两类权重都排第一：国内家庭宽带无需代理即可直连，
 * 2026-10-30 凌晨实测 d1 全 11 文件 417.7 MiB / 78.9 s 通过全部哈希；境外/兜底源在后。
 */
internal object ModelSources {

    /**
     * d1 包单个文件的有序下载源。
     *
     * 1. 魔搭：onnx-community 镜像仓，[revision] 是 HF 提交号，魔搭侧文件可能分属多次
     *    导入提交，故固定取 master —— 正确性由内容哈希保证，不靠版本号。
     * 2. hf-mirror.com：HuggingFace 国内反代，LFS 字节直出。
     * 3. huggingface.co：官方源，海外网络兜底。
     */
    fun d1(repository: String, revision: String, path: String): List<String> {
        // path 已通过 [A-Za-z0-9_./-] 白名单校验，保持原始斜杠（实测魔搭可用；
        // 编码成 %2F 反而可能被网关拒绝）。
        return listOf(
            "https://modelscope.cn/api/v1/models/$repository/repo?Revision=master&FilePath=$path",
            "https://hf-mirror.com/$repository/resolve/$revision/$path",
            "https://huggingface.co/$repository/resolve/$revision/$path",
        )
    }

    /**
     * pose_landmarker_lite.task 的有序下载源。
     *
     * 1. 魔搭：Composable/pose_landmarker_lite，固定到上传该文件的不可变提交。
     * 2. Google Storage：MediaPipe 官方下载地址（国内需代理，海外直连）。
     */
    fun poseTask(): List<String> = listOf(
        "https://modelscope.cn/api/v1/models/Composable/pose_landmarker_lite/repo" +
            "?Revision=b952ec12202f43e88a690e074cc66d86d0d53700&FilePath=pose_landmarker_lite.task",
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/" +
            "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
    )
}
