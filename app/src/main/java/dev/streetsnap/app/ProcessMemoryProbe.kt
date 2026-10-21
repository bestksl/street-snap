package dev.streetsnap.app

import android.os.Debug

/**
 * Real process-memory sample for the developer diagnostics panel. d1 and the pose landmarker
 * run on CPU, so there is no separate device memory to read: the ONNX Runtime arena, the
 * model graphs and MediaPipe's native allocations all live in this process's native space,
 * which [nativePssKb]/[nativeAllocatedKb] reflect. PSS comes from ActivityManager-backed
 * [Debug.getMemoryInfo] (sampled, not free); [Debug.getNativeHeapAllocatedSize] is the live
 * malloc accounting. No new dependency.
 */
internal data class ProcessMemorySnapshot(
    val totalPssKb: Int,
    val nativePssKb: Int,
    val dalvikPssKb: Int,
    val nativeAllocatedKb: Long,
)

internal object ProcessMemoryProbe {
    /** Null on any failure; diagnostics must never crash the camera screen. */
    fun sample(): ProcessMemorySnapshot? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        ProcessMemorySnapshot(
            totalPssKb = info.totalPss,
            nativePssKb = info.nativePss,
            dalvikPssKb = info.dalvikPss,
            nativeAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L,
        )
    }.getOrNull()
}
