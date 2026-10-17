package dev.streetsnap.app.edge

import android.graphics.Bitmap
import java.io.Closeable

/** A model that compares the same A/B/C candidate contact sheet. Busy calls return null. */
internal interface CandidateRanker : Closeable {
    suspend fun rankCandidates(sheet: Bitmap): CropRanking?
}
