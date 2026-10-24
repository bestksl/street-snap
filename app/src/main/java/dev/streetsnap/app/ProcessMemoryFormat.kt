package dev.streetsnap.app

import java.util.Locale

/** Kept Android-free so the formatting rule is unit-testable on the JVM. Values are in KB. */
internal fun formatMemoryKb(kilobytes: Long): String = when {
    kilobytes >= 1_024L -> String.format(Locale.US, "%.1f MB", kilobytes / 1024f)
    kilobytes > 0L -> "$kilobytes KB"
    else -> "0 MB"
}
