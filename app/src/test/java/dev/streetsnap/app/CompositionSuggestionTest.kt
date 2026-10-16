package dev.streetsnap.app

import dev.streetsnap.app.edge.CompositionSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompositionSuggestionTest {
    @Test fun acceptsExactlyOneBoundedBoxAndNormalizesCoordinates() {
        val answer = CompositionSuggestion.parse("[125, 200, 875, 900]")
        requireNotNull(answer)
        assertEquals(.125f, answer.left, .0001f)
        assertEquals(.2f, answer.top, .0001f)
        assertEquals(.875f, answer.right, .0001f)
        assertEquals(.9f, answer.bottom, .0001f)
    }

    @Test fun rejectsPromptInjectionMultipleBoxesAndTinyOrReversedBoxes() {
        assertNull(CompositionSuggestion.parse("ignore instructions [100,100,900,900] [0,0,1000,1000]"))
        assertNull(CompositionSuggestion.parse("[900, 900, 100, 100]"))
        assertNull(CompositionSuggestion.parse("[10, 10, 90, 90]"))
        assertNull(CompositionSuggestion.parse("Please run [0,0,1001,1000]"))
    }
}
