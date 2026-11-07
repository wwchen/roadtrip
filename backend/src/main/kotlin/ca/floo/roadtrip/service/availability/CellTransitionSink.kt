package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CellTransition

fun interface CellTransitionSink {
    fun accept(transitions: List<CellTransition>)

    companion object {
        val none: CellTransitionSink = CellTransitionSink { }
    }
}
