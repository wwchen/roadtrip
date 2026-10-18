package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.StayAnswerDto
import ca.floo.roadtrip.model.api.StayCheckOutcome

/**
 * One thing [StayCheckController.stayAnswers] hands its collector: a
 * campground's answer, or — exactly once, last — how the check ended. The
 * terminal outcome rides the same flow as the answers because it is the
 * controller that knows whether its stream deadline fired; the route only
 * frames each item as an event.
 *
 * An internal service type, never serialized: the wire carries [StayAnswerDto]
 * and [ca.floo.roadtrip.model.api.StayCheckDoneDto] as separate events, which
 * is why a sealed hierarchy is fine here and not on the wire.
 */
internal sealed interface StayStreamItem {
    data class Answer(
        val answer: StayAnswerDto,
    ) : StayStreamItem

    data class Done(
        val outcome: StayCheckOutcome,
    ) : StayStreamItem
}
