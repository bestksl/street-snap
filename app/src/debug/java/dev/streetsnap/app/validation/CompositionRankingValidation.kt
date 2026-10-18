package dev.streetsnap.app.validation

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import dev.streetsnap.app.composition.CandidateContactSheet
import dev.streetsnap.app.composition.CompositionCandidate
import dev.streetsnap.app.composition.FastCompositionAdvisor
import dev.streetsnap.app.edge.CropRanking
import dev.streetsnap.app.edge.EdgeModelCatalog
import dev.streetsnap.app.edge.EdgeModelStore
import dev.streetsnap.app.edge.SmolVlmRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Explicit adb-only diagnostic using an app-private input placed by the developer.
 * It requests no permissions, starts no Activity, downloads nothing, and saves no
 * image or report. A completed run is not a claim that the model passed the bias test.
 */
class CompositionRankingValidation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            runBlocking { validate() }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "\nThree ranking passes completed; interpret selections and blank control before judging quality.\n")
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "\nValidation failed: ${error.javaClass.simpleName}: ${error.message}\n")
            })
        }
    }

    private suspend fun validate() {
        val input = File(targetContext.filesDir, "composition-validation.png")
        require(input.isFile && input.length() in 1..8_000_000L) {
            "Place a PNG snapshot in app files/composition-validation.png before running this diagnostic"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "Invalid snapshot dimensions" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val source = requireNotNull(BitmapFactory.decodeFile(input.absolutePath, options)) { "Cannot decode snapshot" }
        try {
            val model = requireNotNull(EdgeModelCatalog.find("smolvlm-256m"))
            val installed = requireNotNull(EdgeModelStore(targetContext).installed(model)) {
                "No installed SmolVLM 256M bundle; open composition assistance and import the bundled model first"
            }
            emit("model=${model.id} revision=${installed.plan.revision} vision=${installed.plan.visionFile} " +
                "embedding=${installed.plan.embeddingFile} decoder=${installed.plan.decoderFile}")
            emit("input_width=${source.width} input_height=${source.height} input_sample=$sample")
            val candidates = advise(source, "source")
            val openedAt = SystemClock.elapsedRealtime()
            SmolVlmRuntime(installed).use { runtime ->
                emit("session_load_ms=${SystemClock.elapsedRealtime() - openedAt}")
                val forward = rank(runtime, source, candidates, listOf(0, 1, 2), "forward")
                val rotated = rank(runtime, source, candidates, listOf(1, 2, 0), "rotated")
                val blank = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val blankSelection = try {
                    blank.eraseColor(Color.rgb(128, 128, 128))
                    rank(runtime, blank, advise(blank, "blank"), listOf(0, 1, 2), "blank")
                } finally { blank.recycle() }
                val forwardCanonical = forward.selectedIndex
                val rotatedCanonical = rotated.selectedIndex?.let { listOf(1, 2, 0)[it] }
                val agreement = if (forwardCanonical != null && rotatedCanonical != null) {
                    (forwardCanonical == rotatedCanonical).toString()
                } else "unknown_abstention"
                emit("summary completed_passes=3 forward_canonical=$forwardCanonical rotated_canonical=$rotatedCanonical " +
                    "same_content_selected=$agreement blank_selected=${blankSelection.selectedIndex} diagnostic_only=true")
            }
        } finally { source.recycle() }
    }

    private fun advise(source: Bitmap, case: String): List<CompositionCandidate> {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val advice = FastCompositionAdvisor.suggest(source.width, source.height, pixels)
        val candidates = advice.candidates
        require(candidates.size == 3) { "Fast advisor did not return three candidates" }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
        emit("case=$case rule_ms=$elapsedMs distant_subject=${advice.subjectLooksDistant} candidates=" +
            candidates.mapIndexed { index, candidate ->
                val crop = candidate.crop
                "$index:[${crop.left},${crop.top},${crop.right},${crop.bottom}],rule_score=${candidate.score},style=${candidate.style}"
            }.joinToString(";"))
        return candidates
    }

    private suspend fun rank(runtime: SmolVlmRuntime, source: Bitmap, canonical: List<CompositionCandidate>,
        order: List<Int>, case: String): CropRanking {
        val sheetStartedAt = SystemClock.elapsedRealtimeNanos()
        val sheet = CandidateContactSheet.create(source, order.map { canonical[it] })
        val sheetMs = (SystemClock.elapsedRealtimeNanos() - sheetStartedAt) / 1_000_000.0
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = requireNotNull(withTimeout(60_000L) { runtime.rankCandidates(sheet) }) {
                "Ranking did not return scores: runtime busy or invalid logits ($case)"
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val canonicalIndex = result.selectedIndex?.let { order[it] }
            emit("case=$case order=$order sheet_ms=$sheetMs inference_ms=$elapsedMs " +
                "selected_position=${result.selectedIndex} selected_canonical=$canonicalIndex " +
                "support=${result.support} margin=${result.margin} probabilities=${result.probabilities} raw_scores=${result.rawScores}")
            return result
        } finally { sheet.recycle() }
    }

    private fun emit(message: String) {
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
