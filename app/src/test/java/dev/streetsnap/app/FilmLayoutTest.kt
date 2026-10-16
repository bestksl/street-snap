package dev.streetsnap.app

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class FilmLayoutTest {
    @Test fun framesFitTheWholePhotoWithoutChangingAspectOrOutputDimensions() {
        for ((width, height) in listOf(3000f to 4000f, 4000f to 3000f, 1000f to 1000f, 1f to 1f)) {
            for (frame in FilmFrame.entries) {
                val layout = FilmLayout.fit(width, height, frame)
                assertTrue(layout.left >= 0 && layout.top >= 0)
                assertTrue(layout.right <= width && layout.bottom <= height)
                assertEquals(width / height, (layout.right - layout.left) / (layout.bottom - layout.top), .00001f)
                if (frame != FilmFrame.NONE) {
                    assertTrue(layout.left > 0 && layout.top > 0)
                    assertTrue(layout.right < width && layout.bottom < height)
                }
            }
        }
    }

    @Test fun stripReservesFooterAndNoFrameRetainsAllPixels() {
        val plain = FilmLayout.fit(3000f, 4000f, FilmFrame.NONE)
        assertEquals(FilmLayout(0f, 0f, 3000f, 4000f), plain)
        val strip = FilmLayout.fit(3000f, 4000f, FilmFrame.STRIP)
        assertTrue(4000f - strip.bottom > strip.top)
    }

    @Test fun stampsUseCapturedTimeAcrossMidnightAndOffAddsNothing() {
        val shutterTime = LocalDateTime.of(2026, 10, 16, 23, 59)
        assertEquals("26.10.16", FilmStamp.text(DateStamp.DATE, shutterTime))
        assertEquals("26.10.16  23:59", FilmStamp.text(DateStamp.DATE_TIME, shutterTime))
        assertNull(FilmStamp.text(DateStamp.OFF, shutterTime))
    }
}
