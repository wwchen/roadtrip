package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.repo.CatalogMatchRepo
import ca.floo.roadtrip.repo.CatalogMatchRepo.CampsiteNameCandidate
import ca.floo.roadtrip.repo.CatalogMatchRepo.GeoNameCandidate
import ca.floo.roadtrip.repo.CatalogMatchRepo.MatchPair
import kotlinx.serialization.json.JsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for CatalogMatcherService using an in-memory FakeCatalogMatchRepo
 * that records what the service asks it to persist. The repo is `open`
 * specifically so we can drive service-level orchestration without spinning up
 * Postgres — the DB-level SQL behavior is covered by CatalogMatchRepoTest.
 */
class CatalogMatcherServiceTest {
    @Test
    fun `deterministic pass writes shared_vendor_ref matches without geo-name checks`() {
        val sharedPair =
            MatchPair(
                aId = 10L,
                bId = 20L,
                heuristic = CatalogMatchRepo.sharedVendorRefHeuristic(vendor = "recgov", externalId = "232447"),
            )
        val fake =
            FakeCatalogMatchRepo(
                sharedCampgroundPairs = listOf(sharedPair),
                geoNameCandidates = emptyList(),
                campsiteCandidates = emptyList(),
            )
        val service =
            CatalogMatcherService(
                matches = fake,
                config =
                    CatalogMatcherService.MatcherConfig(
                        maxDistanceM = CatalogMatcherService.DEFAULT_MAX_DISTANCE_M,
                        minNameSimilarity = CatalogMatcherService.DEFAULT_MIN_NAME_SIMILARITY,
                    ),
            )

        val stats = service.run()

        assertEquals(1, stats.campgroundPairs)
        assertEquals(0, stats.campsitePairs)
        assertEquals(listOf(sharedPair), fake.upsertedCampgroundPairs)
        assertEquals(
            JsonPrimitive(CatalogMatcherService.METHOD_SHARED_VENDOR_REF),
            fake.upsertedCampgroundPairs.single().heuristic["method"],
        )
    }

    @Test
    fun `heuristic pass matches near and similar-name cross-vendor campgrounds and skips the rest`() {
        val closeSimilar =
            GeoNameCandidate(
                aId = 100L,
                bId = 101L,
                aName = "Upper Pines Campground",
                bName = "Upper Pines",
                distanceM = 42.0,
            )
        val closeDifferentName =
            GeoNameCandidate(
                aId = 200L,
                bId = 201L,
                aName = "Lower Pines",
                bName = "Wawona Meadow Loop",
                distanceM = 30.0,
            )
        val fake =
            FakeCatalogMatchRepo(
                sharedCampgroundPairs = emptyList(),
                geoNameCandidates = listOf(closeSimilar, closeDifferentName),
                campsiteCandidates = emptyList(),
            )
        val service =
            CatalogMatcherService(
                matches = fake,
                config =
                    CatalogMatcherService.MatcherConfig(
                        maxDistanceM = CatalogMatcherService.DEFAULT_MAX_DISTANCE_M,
                        minNameSimilarity = 0.5,
                    ),
            )

        val stats = service.run()

        assertEquals(1, stats.campgroundPairs)
        val pair = fake.upsertedCampgroundPairs.single()
        assertEquals(100L, pair.aId)
        assertEquals(101L, pair.bId)
        assertEquals(JsonPrimitive(CatalogMatcherService.METHOD_GEO_NAME), pair.heuristic["method"])
        assertEquals(JsonPrimitive(42.0), pair.heuristic["distance_m"])
        assertTrue(
            (pair.heuristic["name_similarity"] as JsonPrimitive).content.toDouble() > 0.0,
            "expected non-zero name_similarity",
        )
    }

    @Test
    fun `campsite pass matches on exact normalized loop and name and skips mismatches`() {
        val matching =
            CampsiteNameCandidate(
                aId = 500L,
                bId = 501L,
                aLoop = "A ",
                bLoop = "a",
                aName = "Site 001",
                bName = "site 001",
            )
        val differentName =
            CampsiteNameCandidate(
                aId = 502L,
                bId = 503L,
                aLoop = "A",
                bLoop = "A",
                aName = "Site 001",
                bName = "Site 002",
            )
        val differentLoop =
            CampsiteNameCandidate(
                aId = 504L,
                bId = 505L,
                aLoop = "A",
                bLoop = "B",
                aName = "Site 001",
                bName = "Site 001",
            )
        val bothLoopsNull =
            CampsiteNameCandidate(
                aId = 506L,
                bId = 507L,
                aLoop = null,
                bLoop = null,
                aName = "Site 010",
                bName = "Site 010",
            )
        val fake =
            FakeCatalogMatchRepo(
                sharedCampgroundPairs = emptyList(),
                geoNameCandidates = emptyList(),
                campsiteCandidates = listOf(matching, differentName, differentLoop, bothLoopsNull),
            )
        val service =
            CatalogMatcherService(
                matches = fake,
                config =
                    CatalogMatcherService.MatcherConfig(
                        maxDistanceM = CatalogMatcherService.DEFAULT_MAX_DISTANCE_M,
                        minNameSimilarity = CatalogMatcherService.DEFAULT_MIN_NAME_SIMILARITY,
                    ),
            )

        val stats = service.run()

        assertEquals(2, stats.campsitePairs)
        val ids = fake.upsertedCampsitePairs.map { it.aId to it.bId }.toSet()
        assertEquals(setOf(500L to 501L, 506L to 507L), ids)
        val heuristic = fake.upsertedCampsitePairs.first { it.aId == 500L }.heuristic
        assertEquals(JsonPrimitive(CatalogMatcherService.METHOD_GEO_NAME), heuristic["method"])
        assertEquals(JsonPrimitive("loop+name"), heuristic["matched_on"])
    }

    @Test
    fun `MatcherConfig thresholds honored — high threshold skips below-threshold pairs`() {
        val borderline =
            GeoNameCandidate(
                aId = 700L,
                bId = 701L,
                aName = "River Bend",
                bName = "River Bend Campground",
                distanceM = 10.0,
            )
        val fake =
            FakeCatalogMatchRepo(
                sharedCampgroundPairs = emptyList(),
                geoNameCandidates = listOf(borderline),
                campsiteCandidates = emptyList(),
            )
        val strict =
            CatalogMatcherService(
                matches = fake,
                config =
                    CatalogMatcherService.MatcherConfig(
                        maxDistanceM = CatalogMatcherService.DEFAULT_MAX_DISTANCE_M,
                        minNameSimilarity = 0.99,
                    ),
            )
        val strictStats = strict.run()
        assertEquals(0, strictStats.campgroundPairs)
        assertEquals(0, fake.upsertedCampgroundPairs.size)

        val fakeLoose =
            FakeCatalogMatchRepo(
                sharedCampgroundPairs = emptyList(),
                geoNameCandidates = listOf(borderline),
                campsiteCandidates = emptyList(),
            )
        val loose =
            CatalogMatcherService(
                matches = fakeLoose,
                config =
                    CatalogMatcherService.MatcherConfig(
                        maxDistanceM = CatalogMatcherService.DEFAULT_MAX_DISTANCE_M,
                        minNameSimilarity = 0.4,
                    ),
            )
        val looseStats = loose.run()
        assertEquals(1, looseStats.campgroundPairs)
    }

    @Test
    fun `run returns MatchRunStats reflecting all three counts`() {
        val shared =
            MatchPair(
                aId = 1L,
                bId = 2L,
                heuristic = CatalogMatchRepo.sharedVendorRefHeuristic("recgov", "1"),
            )
        val geo =
            GeoNameCandidate(
                aId = 3L,
                bId = 4L,
                aName = "Camp Fern",
                bName = "Camp Fern",
                distanceM = 5.0,
            )
        val site =
            CampsiteNameCandidate(
                aId = 5L,
                bId = 6L,
                aLoop = "A",
                bLoop = "A",
                aName = "01",
                bName = "01",
            )
        val fake =
            FakeCatalogMatchRepo(
                sharedCampgroundPairs = listOf(shared),
                geoNameCandidates = listOf(geo),
                campsiteCandidates = listOf(site),
                recomputeReturns = 42,
            )
        val service =
            CatalogMatcherService(
                matches = fake,
                config =
                    CatalogMatcherService.MatcherConfig(
                        maxDistanceM = CatalogMatcherService.DEFAULT_MAX_DISTANCE_M,
                        minNameSimilarity = 0.5,
                    ),
            )

        val stats = service.run()

        assertEquals(2, stats.campgroundPairs)
        assertEquals(1, stats.campsitePairs)
        // Only the FINAL recompute value is surfaced.
        assertEquals(42, stats.groupsRecomputed)
        // Three recompute calls in the pipeline (post-shared, post-geo, final).
        assertEquals(EXPECTED_RECOMPUTE_CALLS, fake.recomputeCalls)
    }

    @Test
    fun `MatcherConfig fromEnv uses defaults when env vars missing and parses provided values`() {
        val defaults = CatalogMatcherService.MatcherConfig.fromEnv(emptyMap())
        assertEquals(CatalogMatcherService.DEFAULT_MAX_DISTANCE_M, defaults.maxDistanceM)
        assertEquals(CatalogMatcherService.DEFAULT_MIN_NAME_SIMILARITY, defaults.minNameSimilarity)

        val overridden =
            CatalogMatcherService.MatcherConfig.fromEnv(
                mapOf(
                    CatalogMatcherService.ENV_MAX_DISTANCE_M to "250.0",
                    CatalogMatcherService.ENV_MIN_NAME_SIMILARITY to "0.75",
                ),
            )
        assertEquals(250.0, overridden.maxDistanceM)
        assertEquals(0.75, overridden.minNameSimilarity)
    }

    private class FakeCatalogMatchRepo(
        private val sharedCampgroundPairs: List<MatchPair>,
        private val geoNameCandidates: List<GeoNameCandidate>,
        private val campsiteCandidates: List<CampsiteNameCandidate>,
        private val recomputeReturns: Int = 0,
    ) : CatalogMatchRepo(DSL.using(SQLDialect.POSTGRES)) {
        val upsertedCampgroundPairs = mutableListOf<MatchPair>()
        val upsertedCampsitePairs = mutableListOf<MatchPair>()
        var recomputeCalls: Int = 0

        override fun sharedVendorRefCampgroundPairs(): List<MatchPair> = sharedCampgroundPairs

        override fun sharedVendorRefCampsitePairs(): List<MatchPair> = emptyList()

        override fun geoNameCampgroundCandidates(maxDistanceM: Double): List<GeoNameCandidate> = geoNameCandidates

        override fun campsiteNameCandidates(): List<CampsiteNameCandidate> = campsiteCandidates

        override fun upsertCampgroundMatches(pairs: List<MatchPair>): Int {
            upsertedCampgroundPairs.addAll(pairs)
            return pairs.size
        }

        override fun upsertCampsiteMatches(pairs: List<MatchPair>): Int {
            upsertedCampsitePairs.addAll(pairs)
            return pairs.size
        }

        override fun recomputeMatchGroups(): Int {
            recomputeCalls += 1
            return recomputeReturns
        }
    }

    companion object {
        private const val EXPECTED_RECOMPUTE_CALLS = 3
    }
}
