package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PoiServingRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `campgrounds sharing a booking ref collapse to one pin inside a corridor`() {
        val shared =
            seed(source = "campflare", sourceId = "cf-1", bookingProvider = "recgov", bookingProviderRef = "232447")
        seed(source = "recgov", sourceId = "232447", bookingProvider = "recgov", bookingProviderRef = "232447")
        val other =
            seed(source = "recgov", sourceId = "232448", bookingProvider = "recgov", bookingProviderRef = "232448")

        val rows = repo().fetchPoisWithinPolygon(WORLD, listOf("campground"))

        assertEquals(setOf(shared, other), rows.map { it.id }.toSet())
    }

    @Test
    fun `campgrounds from one data provider sharing a booking ref are both pins`() {
        val a =
            seed(
                source = "aspira",
                sourceId = "bc-1",
                bookingProvider = "aspira",
                bookingProviderRef = SHARED_ASPIRA_REF,
            )
        val b =
            seed(
                source = "aspira",
                sourceId = "bc-2",
                bookingProvider = "aspira",
                bookingProviderRef = SHARED_ASPIRA_REF,
            )

        val rows = repo(enabledDataProviders = setOf("aspira")).fetchPoisWithinPolygon(WORLD, listOf("campground"))

        assertEquals(setOf(a, b), rows.map { it.id }.toSet())
    }

    @Test
    fun `campgrounds without a booking ref dedupe on data provider identity`() {
        val a = seed(source = "recgov", sourceId = "1", bookingProvider = null, bookingProviderRef = null)
        val b = seed(source = "recgov", sourceId = "2", bookingProvider = null, bookingProviderRef = null)

        val rows = repo().fetchPoisWithinPolygon(WORLD, listOf("campground"))

        assertEquals(setOf(a, b), rows.map { it.id }.toSet())
    }

    @Test
    fun `campgrounds with a booking provider but no ref fall back to data provider identity`() {
        val a = seed(source = "recgov", sourceId = "1", bookingProvider = "aspira", bookingProviderRef = null)
        val b = seed(source = "recgov", sourceId = "2", bookingProvider = "aspira", bookingProviderRef = null)

        val rows = repo().fetchPoisWithinPolygon(WORLD, listOf("campground"))

        assertEquals(setOf(a, b), rows.map { it.id }.toSet())
    }

    private fun repo(enabledDataProviders: Set<String> = setOf("recgov", "campflare")) =
        PoiServingRepo(ctx, enabledDataProviders = enabledDataProviders)

    private fun seed(
        source: String,
        sourceId: String,
        bookingProvider: String?,
        bookingProviderRef: String?,
    ): Long =
        ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = "Camp $sourceId",
                lon = CAMP_LON,
                lat = CAMP_LAT,
                source = source,
                subcategory = "federal",
                agency = "USFS",
                region = "OR",
                country = "US",
                providerRefJson = "{}",
                propertiesJson = "{}",
                bookingProvider = bookingProvider,
                bookingProviderRef = bookingProviderRef,
            ).poiId

    private companion object {
        const val WORLD = """{"type":"Polygon","coordinates":[[[-180,-89],[180,-89],[180,89],[-180,89],[-180,-89]]]}"""
        const val CAMP_LON = -120.0
        const val CAMP_LAT = 45.0
        const val SHARED_ASPIRA_REF = "pc:1005:-2147483645:9002"
    }
}
