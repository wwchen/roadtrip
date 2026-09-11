package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.Repos
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

internal fun productionEtlRegistry(repos: Repos): Map<String, TerminalEtlBinding<*, *>> =
    productionTerminalEtlDefinitions.mapValues { (_, definition) -> definition.bind(repos) }

internal val productionTerminalEtlDefinitions: Map<String, TerminalEtlDefinition<*, *>> by lazy {
    val registry = PoiRegistry.loadResource("poi-registry.yaml")
    val out = mutableMapOf<String, TerminalEtlDefinition<*, *>>()
    for (row in registry.enabledPoiData()) {
        val terminal = row.etls.last()
        out[terminal.slug] = createPoiTerminal(terminal)
    }
    for (row in registry.enabledCampsiteData()) {
        val terminal = row.etls.last()
        out[terminal.slug] = createCampsiteTerminal(terminal)
    }
    out
}

internal data class PoiAdapterSpec(
    val dataProvider: DataProvider,
    val create: (EtlEntry) -> TerminalEtlDefinition<*, *>,
)

internal val poiAdapters: Map<String, PoiAdapterSpec> =
    mapOf(
        "CampflareCampgroundsEtl" to PoiAdapterSpec(DataProvider.CAMPFLARE) { campgroundSink(CampflareCampgroundsEtl()) },
        "RecGovCampgroundsEtl" to PoiAdapterSpec(DataProvider.RECGOV) { campgroundSink(RecGovCampgroundsEtl(it.slug)) },
        "AspiraCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.ASPIRA) { entry ->
                campgroundSink(
                    AspiraCampgroundsEtl(
                        etlSlug = entry.slug,
                        aspiraTenant = entry.args.require("tenant"),
                        geometry = entry.requireGeometry(),
                    ),
                )
            },
        "BcParksCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.STRAPI) { entry ->
                campgroundSink(
                    BcParksCampgroundsEtl(
                        etlSlug = entry.slug,
                        aspiraTenant = entry.args.require("tenant"),
                        geometry = entry.requireGeometry(),
                    ),
                )
            },
        "ReserveAmericaCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.RESERVEAMERICA) { entry ->
                campgroundSink(ReserveAmericaCampgroundsEtl(entry.slug, contractCode = entry.args.require("contract")))
            },
        "ReserveCaliforniaCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.RESERVECALIFORNIA) { campgroundSink(ReserveCaliforniaCampgroundsEtl(it.slug)) },
        "PlanetFitnessEtl" to PoiAdapterSpec(DataProvider.PLANET_FITNESS_LOCATION) { planetFitnessSink(PlanetFitnessEtl()) },
        "TeslaIndexEtl" to PoiAdapterSpec(DataProvider.TESLA_SUPERCHARGER) { teslaSuperchargerSink(TeslaIndexEtl()) },
    )

internal val campsiteAdapters: Map<String, (EtlEntry) -> TerminalEtlDefinition<*, *>> =
    mapOf(
        "CampflareCampsitesEtl" to { _ -> campsiteSink(CampflareCampsitesEtl()) },
        "RecGovCampsitesEtl" to { entry -> campsiteSink(RecGovCampsitesEtl(entry.slug)) },
        "AspiraCampsitesEtl" to { entry ->
            campsiteSink(
                AspiraCampsitesEtl(
                    etlSlug = entry.slug,
                    mapsInputSlug = entry.args.require("maps_input"),
                    inventoryInputSlug = entry.args.require("inventory_input"),
                    dictionariesInputSlug = entry.args["dictionaries_input"],
                    aspiraTenant = entry.args.require("tenant"),
                    parentDataProvider =
                        entry.args["parent_data_provider"]
                            ?.let { DataProvider.fromId(it) } ?: DataProvider.ASPIRA,
                ),
            )
        },
        "ReserveAmericaSitesEtl" to { entry ->
            campsiteSink(
                ReserveAmericaSitesEtl(
                    etlSlug = entry.slug,
                    contractCode = entry.args.require("contract"),
                ),
            )
        },
        "ReserveCaliforniaSitesEtl" to { entry -> campsiteSink(ReserveCaliforniaSitesEtl(entry.slug)) },
    )

internal fun dataProviderForAdapter(adapter: String): DataProvider? = poiAdapters[adapter]?.dataProvider

private fun createPoiTerminal(entry: EtlEntry): TerminalEtlDefinition<*, *> =
    poiAdapters[entry.adapter]?.create?.invoke(entry)
        ?: error("Unknown poi_data adapter: ${entry.adapter} (slug=${entry.slug})")

private fun createCampsiteTerminal(entry: EtlEntry): TerminalEtlDefinition<*, *> =
    campsiteAdapters[entry.adapter]?.invoke(entry)
        ?: error("Unknown campsite_data adapter: ${entry.adapter} (slug=${entry.slug})")

private fun Map<String, String>.require(key: String): String = this[key] ?: error("Missing required ETL arg '$key'")

/** Validation guarantees presence; this is the belt. */
private fun EtlEntry.requireGeometry(): GeometryPolicy = geometry ?: error("$slug: geometry policy is required for $adapter")

private fun <DTO> campgroundSink(etl: SourceEtl<DTO, CampgroundUpsertCandidate>): TerminalEtlDefinition<DTO, CampgroundUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records -> FlushCounts(upserted = repos.campgrounds.upsertCampgroundBatch(records)) }
    }

private fun <DTO> campsiteSink(etl: SourceEtl<DTO, CampsiteUpsertCandidate>): TerminalEtlDefinition<DTO, CampsiteUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records ->
            val (upserted, skipped) = repos.campsites.upsertCampsiteBatch(records)
            FlushCounts(upserted = upserted, skipped = skipped)
        }
    }

private fun <DTO> teslaSuperchargerSink(
    etl: SourceEtl<DTO, TeslaSuperchargerUpsertCandidate>,
): TerminalEtlDefinition<DTO, TeslaSuperchargerUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records -> FlushCounts(upserted = repos.teslaSuperchargers.upsertTeslaSuperchargerBatch(records)) }
    }

private fun <DTO> planetFitnessSink(
    etl: SourceEtl<DTO, PlanetFitnessLocationUpsertCandidate>,
): TerminalEtlDefinition<DTO, PlanetFitnessLocationUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records -> FlushCounts(upserted = repos.planetFitnessLocations.upsertPlanetFitnessLocationBatch(records)) }
    }
