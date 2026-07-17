package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.Fetcher
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry

private const val TEST_RECGOV_SOURCE = "test"
private const val TEST_RECGOV_DATA_SOURCE = "test-recgov-source"

internal fun recgovAvailabilityPoiRegistry(source: String = TEST_RECGOV_SOURCE): PoiRegistry =
    availabilityPoiRegistry(source to "RecGovCampgroundsEtl")

internal fun recgovAndCampflareAvailabilityPoiRegistry(
    recgovSource: String = TEST_RECGOV_SOURCE,
    campflareSource: String = "campflare-campgrounds",
): PoiRegistry =
    availabilityPoiRegistry(
        recgovSource to "RecGovCampgroundsEtl",
        campflareSource to "CampflareCampgroundsEtl",
    )

internal fun availabilityPoiRegistry(vararg sourceAdapters: Pair<String, String>): PoiRegistry =
    PoiRegistry(
        dataSources =
            listOf(
                DataSourceEntry(
                    slug = TEST_RECGOV_DATA_SOURCE,
                    name = "Test RecGov",
                    fetcher =
                        Fetcher(
                            executor = "test",
                            filename = "test.json",
                            outputDirPrefix = "test",
                        ),
                ),
            ),
        poiData =
            sourceAdapters.map { (source, adapter) ->
                PoiDataEntry(
                    name = "Test $source",
                    category = "campground",
                    etls =
                        listOf(
                            EtlEntry(
                                slug = source,
                                adapter = adapter,
                                inputs = listOf(TEST_RECGOV_DATA_SOURCE),
                            ),
                        ),
                )
            },
    )

internal fun emptyPoiRegistry(): PoiRegistry = PoiRegistry(dataSources = emptyList(), poiData = emptyList())
