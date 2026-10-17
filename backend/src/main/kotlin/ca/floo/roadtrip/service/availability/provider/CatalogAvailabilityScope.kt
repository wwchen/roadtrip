package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.domain.Campsite
import java.time.LocalDate

/**
 * The campsites one catalog availability call is about, in the two senses a
 * single list used to conflate.
 *
 * [answerFor] is what the call *answers*: the campsites whose cells the caller
 * puts in its response, and the only campsites an answer-level fact may be
 * derived from. [alsoCover] is the rest of the campground — cells worth keeping
 * because the caller has already paid for the upstream call, and nothing more.
 * An adapter widens to them only where its own upstream shape makes that free;
 * one that would have to issue another call is entitled to ignore them.
 *
 * The two lists are disjoint, so there is no single "every campsite" list to
 * reach for by accident. An adapter that wants every campsite it may key an
 * observation to asks for [covered], which reads as what it is.
 *
 * Answer-level facts are not an adapter's to compute: [catalogAvailability]
 * derives the season block out here, from [answerFor], over the per-campsite
 * material the batch carries.
 */
class CatalogAvailabilityScope private constructor(
    val answerFor: List<Campsite>,
    val alsoCover: List<Campsite>,
) {
    /**
     * Every campsite this call may key an observation to: the answered ones
     * first, then the free extras.
     */
    val covered: List<Campsite> get() = answerFor + alsoCover

    /**
     * The earliest of [reopensOnByCampsite] across the answered campsites, as a
     * season block. A campsite the caller filtered out cannot reach it, which is
     * the whole point of asking the scope rather than reducing over a list.
     */
    fun seasonBlockFrom(reopensOnByCampsite: Map<Long, LocalDate>): AvailabilitySeasonBlock? =
        answerFor
            .mapNotNull { reopensOnByCampsite[it.id] }
            .minOrNull()
            ?.let { AvailabilitySeasonBlock(reopensOn = it.toString()) }

    companion object {
        /** A call that answers for every campsite it is given and asks for nothing more. */
        fun of(campsites: List<Campsite>): CatalogAvailabilityScope = CatalogAvailabilityScope(campsites, emptyList())

        /**
         * A filtered call: it answers for [answerFor] and would keep the rest of
         * [campgroundCampsites] if the adapter can reach them for free.
         */
        fun of(
            answerFor: List<Campsite>,
            campgroundCampsites: List<Campsite>,
        ): CatalogAvailabilityScope {
            val answeredIds = answerFor.mapTo(mutableSetOf()) { it.id }
            return CatalogAvailabilityScope(answerFor, campgroundCampsites.filterNot { it.id in answeredIds })
        }
    }
}
