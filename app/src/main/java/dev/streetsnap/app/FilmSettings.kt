package dev.streetsnap.app

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

enum class FilmFrame(val id: String, val label: String) {
    NONE("NONE", "无"), WHITE("WHITE", "白边"), BLACK("BLACK", "黑边"), STRIP("STRIP", "底片边");

    companion object {
        fun fromId(id: String?): FilmFrame = entries.firstOrNull { it.id == id } ?: NONE
    }
}

enum class DateStamp(val id: String, val label: String) {
    OFF("OFF", "关闭"), DATE("DATE", "日期"), DATE_TIME("DATE_TIME", "日期·时间");

    companion object {
        fun fromId(id: String?): DateStamp = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** Immutable shutter-time decoration; timestamp is captured before entering the processing queue. */
data class FilmRenderSpec(val frame: FilmFrame = FilmFrame.NONE, val stamp: DateStamp = DateStamp.OFF)

object FilmStamp {
    private val dateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd", java.util.Locale.ROOT)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yy.MM.dd  HH:mm", java.util.Locale.ROOT)

    fun text(stamp: DateStamp, capturedAt: LocalDateTime): String? = when (stamp) {
        DateStamp.OFF -> null
        DateStamp.DATE -> capturedAt.format(dateFormatter)
        DateStamp.DATE_TIME -> capturedAt.format(dateTimeFormatter)
    }
}

/** Shared output/thumbnail layout. Fit the entire photo inside the frame, with no edge cropping. */
data class FilmLayout(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        fun fit(width: Float, height: Float, frame: FilmFrame): FilmLayout {
            require(width > 0f && height > 0f && width.isFinite() && height.isFinite())
            if (frame == FilmFrame.NONE) return FilmLayout(0f, 0f, width, height)
            val shortSide = min(width, height)
            val margin = shortSide * .045f
            val footer = if (frame == FilmFrame.STRIP) shortSide * .12f else margin
            val scale = min((width - 2 * margin) / width, (height - margin - footer) / height)
            val photoWidth = width * scale
            val photoHeight = height * scale
            val left = (width - photoWidth) / 2
            val top = margin + (height - margin - footer - photoHeight) / 2
            return FilmLayout(left, top, left + photoWidth, top + photoHeight)
        }
    }
}
