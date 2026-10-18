package dev.streetsnap.app.composition

/** A model choice must follow the same crop when every presentation position changes. */
internal class CandidateSelectionGate(validationOrder: List<Int>) {
    val validationOrder = validationOrder.toList()

    enum class Verdict { ACCEPTED, ABSTAINED, INCONSISTENT, INSUFFICIENT_IMAGE_SIGNAL }

    data class Decision(val verdict: Verdict, val canonicalIndex: Int? = null)

    init {
        require(this.validationOrder.size >= 2)
        require(this.validationOrder.sorted() == this.validationOrder.indices.toList())
        require(this.validationOrder.indices.all { this.validationOrder[it] != it }) {
            "Every candidate must change presentation position"
        }
    }

    fun canonicalForValidation(position: Int?): Int? = position?.let(validationOrder::getOrNull)

    fun evaluate(firstPosition: Int?, validationPosition: Int?, hasVisualEvidence: Boolean): Decision {
        if (!hasVisualEvidence) return Decision(Verdict.INSUFFICIENT_IMAGE_SIGNAL)
        val first = firstPosition?.takeIf { it in validationOrder.indices }
            ?: return Decision(Verdict.ABSTAINED)
        val second = canonicalForValidation(validationPosition) ?: return Decision(Verdict.ABSTAINED)
        return if (first == second) Decision(Verdict.ACCEPTED, first)
        else Decision(Verdict.INCONSISTENT)
    }
}
