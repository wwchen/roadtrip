package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampgroundsEtl
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampsitesEtl
import ca.floo.roadtrip.service.etl.vendors.bcparks.BcParksCampgroundsEtl
import ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampgroundsEtl
import ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampsitesEtl
import ca.floo.roadtrip.service.etl.vendors.osmpf.PlanetFitnessEtl
import ca.floo.roadtrip.service.etl.vendors.recgov.RecGovCampgroundsEtl
import ca.floo.roadtrip.service.etl.vendors.recgov.RecGovCampsitesEtl
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaCampgroundsEtl
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaSitesEtl
import ca.floo.roadtrip.service.etl.vendors.reservecalifornia.ReserveCaliforniaCampgroundsEtl
import ca.floo.roadtrip.service.etl.vendors.reservecalifornia.ReserveCaliforniaSitesEtl
import ca.floo.roadtrip.service.etl.vendors.tesla.TeslaIndexEtl
import org.jooq.DSLContext

internal fun productionEtlRegistry(ctx: DSLContext): Map<String, TerminalEtlBinding<*, *>> =
    productionTerminalEtlDefinitions.mapValues { (_, definition) -> definition.bind(ctx) }

internal val productionTerminalEtlDefinitions: Map<String, TerminalEtlDefinition<*, *>> =
    mapOf(
        // Campflare
        "campflare-campgrounds" to
            campgroundTerminal(CampflareCampgroundsEtl()),
        "campflare-campsites" to
            campsiteTerminal(CampflareCampsitesEtl()),
        // Rec.gov
        "recgov-campgrounds" to
            campgroundTerminal(RecGovCampgroundsEtl("recgov-campgrounds")),
        "recgov-campsites" to
            campsiteTerminal(RecGovCampsitesEtl("recgov-campsites")),
        // Aspira WA
        "aspira-wa-campgrounds" to
            campgroundTerminal(AspiraCampgroundsEtl("aspira-wa-campgrounds", DataProvider.ASPIRA, "wa")),
        "aspira-wa-campsites" to
            campsiteTerminal(
                AspiraCampsitesEtl(
                    etlSlug = "aspira-wa-campsites",
                    mapsInputSlug = "aspira-maps-wa",
                    inventoryInputSlug = "aspira-inventory-wa",
                    dictionariesInputSlug = "aspira-dictionaries-wa",
                    aspiraTenant = "wa",
                    parentDataProvider = DataProvider.ASPIRA,
                ),
            ),
        // Aspira BC
        "aspira-bc-campgrounds" to
            campgroundTerminal(BcParksCampgroundsEtl()),
        "aspira-bc-campsites" to
            campsiteTerminal(
                AspiraCampsitesEtl(
                    etlSlug = "aspira-bc-campsites",
                    mapsInputSlug = "aspira-maps-bc",
                    inventoryInputSlug = "aspira-inventory-bc",
                    dictionariesInputSlug = "aspira-dictionaries-bc",
                    aspiraTenant = "bc",
                    parentDataProvider = DataProvider.STRAPI,
                ),
            ),
        // Aspira PC
        "aspira-pc-campgrounds" to
            campgroundTerminal(AspiraCampgroundsEtl("aspira-pc-campgrounds", DataProvider.ASPIRA, "pc")),
        "aspira-pc-campsites" to
            campsiteTerminal(
                AspiraCampsitesEtl(
                    etlSlug = "aspira-pc-campsites",
                    mapsInputSlug = "aspira-maps-pc",
                    inventoryInputSlug = "aspira-inventory-pc",
                    dictionariesInputSlug = "aspira-dictionaries-pc",
                    aspiraTenant = "pc",
                    parentDataProvider = DataProvider.ASPIRA,
                ),
            ),
        // ReserveAmerica AB
        "reserveamerica-ab-campgrounds" to
            campgroundTerminal(ReserveAmericaCampgroundsEtl("reserveamerica-ab-campgrounds")),
        "reserveamerica-ab-campsites" to
            campsiteTerminal(ReserveAmericaSitesEtl("reserveamerica-ab-campsites", "ABPP")),
        // ReserveAmerica NY
        "reserveamerica-ny-campgrounds" to
            campgroundTerminal(ReserveAmericaCampgroundsEtl("reserveamerica-ny-campgrounds")),
        "reserveamerica-ny-campsites" to
            campsiteTerminal(ReserveAmericaSitesEtl("reserveamerica-ny-campsites", "NY")),
        // ReserveCalifornia
        "reservecalifornia-campgrounds" to
            campgroundTerminal(ReserveCaliforniaCampgroundsEtl("reservecalifornia-campgrounds")),
        "reservecalifornia-campsites" to
            campsiteTerminal(ReserveCaliforniaSitesEtl("reservecalifornia-campsites")),
        // Planet Fitness
        "planet-fitness" to
            planetFitnessTerminal(PlanetFitnessEtl()),
        // Tesla
        "tesla-superchargers" to
            teslaSuperchargerTerminal(TeslaIndexEtl()),
    )

private fun <DTO> campgroundTerminal(
    etl: SourceEtl<DTO, CampgroundUpsertCandidate>,
): TerminalEtlDefinition<DTO, CampgroundUpsertCandidate> =
    TerminalEtlDefinition(etl) { ctx ->
        val repo = CampgroundRepo(ctx)
        terminalSink { records -> FlushCounts(upserted = repo.upsertCampgroundBatch(records)) }
    }

private fun <DTO> campsiteTerminal(etl: SourceEtl<DTO, CampsiteUpsertCandidate>): TerminalEtlDefinition<DTO, CampsiteUpsertCandidate> =
    TerminalEtlDefinition(etl) { ctx ->
        val repo = CampsiteRepo(ctx)
        terminalSink { records ->
            val (upserted, skipped) = repo.upsertCampsiteBatch(records)
            FlushCounts(upserted = upserted, skipped = skipped)
        }
    }

private fun <DTO> teslaSuperchargerTerminal(
    etl: SourceEtl<DTO, TeslaSuperchargerUpsertCandidate>,
): TerminalEtlDefinition<DTO, TeslaSuperchargerUpsertCandidate> =
    TerminalEtlDefinition(etl) { ctx ->
        val repo = TeslaSuperchargerRepo(ctx)
        terminalSink { records -> FlushCounts(upserted = repo.upsertTeslaSuperchargerBatch(records)) }
    }

private fun <DTO> planetFitnessTerminal(
    etl: SourceEtl<DTO, PlanetFitnessLocationUpsertCandidate>,
): TerminalEtlDefinition<DTO, PlanetFitnessLocationUpsertCandidate> =
    TerminalEtlDefinition(etl) { ctx ->
        val repo = PlanetFitnessLocationRepo(ctx)
        terminalSink { records -> FlushCounts(upserted = repo.upsertPlanetFitnessLocationBatch(records)) }
    }
