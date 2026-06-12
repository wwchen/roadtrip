package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.repo.ReservableRepo

/**
 * Output shape for terminal ETLs in the `reservable_data` section
 * (RFC 0008). Just a list of catalog rows to upsert — no POI links.
 *
 * POI linking is a separate concern handled by [PoiReservableJoiner]
 * adapters in the `poi_reservable_joiner` section. Reservable ETLs only
 * know their own upstream's identity scheme; they don't know how each
 * vendor keys its POIs. Joiners own that mapping.
 *
 * The orchestrator dispatches a `reservable_data` row's terminal stage
 * by section, not by marker interface. The terminal etl returns
 * [ReservableEtlOutput]; the orchestrator iterates and calls
 * [ReservableRepo.upsert].
 */
data class ReservableEtlOutput(
    val reservables: List<ReservableRepo.Input>,
)
