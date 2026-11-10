package ca.floo.roadtrip.route

import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_MAX_POLL_WINDOW_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campgroundBatch
import ca.floo.roadtrip.model.api.AvailabilityEgressMode
import ca.floo.roadtrip.model.api.AvailabilityEgressRequestDto
import ca.floo.roadtrip.model.api.AvailabilityEgressResponseDto
import ca.floo.roadtrip.model.api.ProviderRefDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.internal.availabilityEgressRoutes
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityEgressController
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CellTransitionSink
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EGRESS_PATH = "/api/internal/availability/fetch"
private const val CAMPGROUND_REF = "egress-cg"
private const val CAMPSITE_REF = "egress-site-1"
private const val SECOND_CAMPSITE_REF = "egress-site-2"
private const val WINDOW_LEAD_DAYS = 2L
private const val WINDOW_DAYS = 3L
private const val DEFAULT_WEEK_DAYS = 7L
private val observedAt: Instant = Instant.parse("2026-07-30T12:00:00Z")
private val alwaysFresh: Duration = Duration.ofDays(3650)
private val alwaysStale: Duration = Duration.ZERO
private val wire = Json { ignoreUnknownKeys = true }

class AvailabilityEgressRoutesTest : SharedDbTest() {
    private val start: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(WINDOW_LEAD_DAYS)
    private val end: LocalDate = start.plusDays(WINDOW_DAYS)

    @BeforeEach
    fun seed() {
        ctx.cleanCanonicalCatalogFixtures()
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = CAMPGROUND_REF,
                name = "Egress CG",
                lon = -119.56,
                lat = 37.74,
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = CAMPSITE_REF)
        ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = SECOND_CAMPSITE_REF)
    }

    private fun request(
        ref: String = CAMPGROUND_REF,
        startDate: LocalDate? = start,
        endDate: LocalDate? = end,
        mode: AvailabilityEgressMode = AvailabilityEgressMode.REFRESH,
        freshAtOrAfter: String? = null,
    ) = AvailabilityEgressRequestDto(
        mode = mode,
        campground = ProviderRefDto(dataProvider = DataProvider.RECGOV.id, dataProviderRef = ref),
        startDate = startDate?.toString(),
        endDate = endDate?.toString(),
        freshAtOrAfter = freshAtOrAfter,
    )

    private fun controller(
        providers: List<AvailabilityProvider>,
        ttl: Duration,
    ): AvailabilityEgressController {
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        return AvailabilityEgressController(
            campgroundRepo = CampgroundRepo(ctx),
            campsitesRepo = CampsiteRepo(ctx),
            availabilityService =
                CampsiteAvailabilityService(
                    availabilityProviders = providers,
                    dateResolver = dateResolver,
                    failoverFetcher = FailoverAvailabilityFetcher(ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))),
                    bookingHorizons = BookingHorizonResolver(providers, dateResolver),
                    availabilityRepo = AvailabilityRepo(ctx),
                    transitionSink = CellTransitionSink.none,
                    snapshotFreshnessTtl = { ttl },
                ),
            dateResolver = dateResolver,
            availabilityRepo = AvailabilityRepo(ctx),
        )
    }

    private fun recgov(onFailure: AvailabilityProviderError? = null) =
        FakeAvailabilityProvider(BookingProvider.RECGOV) { call ->
            if (onFailure != null) throw onFailure
            campgroundBatch(call, BookingProvider.RECGOV, observedAt = observedAt)
        }

    private fun egress(
        providers: List<AvailabilityProvider>,
        ttl: Duration = alwaysStale,
        enabled: Boolean = true,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            routeTestApplication {
                availabilityEgressRoutes(controller(providers, ttl), enabled)
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(body: AvailabilityEgressRequestDto): HttpResponse =
        client.post(EGRESS_PATH) {
            contentType(ContentType.Application.Json)
            setBody(wire.encodeToString(AvailabilityEgressRequestDto.serializer(), body))
        }

    private fun errorOf(response: String): String =
        wire
            .parseToJsonElement(response)
            .jsonObject
            .getValue("error")
            .jsonPrimitive.content

    @Test
    fun `no credentials are needed once the route is enabled`() =
        egress(listOf(recgov())) {
            assertEquals(HttpStatusCode.OK, post(request()).status)
        }

    @Test
    fun `an unknown campground pair is 404 not_found`() =
        egress(listOf(recgov())) {
            val response = post(request(ref = "nobody"))
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("not_found", errorOf(response.bodyAsText()))
        }

    @Test
    fun `a reversed window is 400 bad_date_window`() =
        egress(listOf(recgov())) {
            val response = post(request(startDate = end, endDate = start))
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a request without a mode is 400 and calls no provider`() {
        val provider = recgov()
        egress(listOf(provider)) {
            val response =
                client.post(EGRESS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"campground":{"data_provider":"${DataProvider.RECGOV.id}","data_provider_ref":"$CAMPGROUND_REF"}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, provider.calls.size)
        }
    }

    @Test
    fun `a campground no provider serves is 501`() =
        egress(listOf(FakeAvailabilityProvider(BookingProvider.ASPIRA))) {
            val response = post(request())
            assertEquals(HttpStatusCode.NotImplemented, response.status)
            assertEquals("unknown_campground", errorOf(response.bodyAsText()))
        }

    @Test
    fun `a stale window calls the provider and returns its cells with their observed_at`() {
        val provider = recgov()
        egress(listOf(provider)) {
            val response = post(request())
            assertEquals(HttpStatusCode.OK, response.status)
            val body = wire.decodeFromString(AvailabilityEgressResponseDto.serializer(), response.bodyAsText())
            assertEquals(1, provider.calls.size)
            assertEquals((WINDOW_DAYS * 2).toInt(), body.cells.size)
            assertEquals(setOf(CAMPSITE_REF, SECOND_CAMPSITE_REF), body.cells.map { it.campsite.dataProviderRef }.toSet())
            assertTrue(body.cells.all { it.campsite.dataProvider == DataProvider.RECGOV.id })
            assertTrue(body.cells.all { Instant.parse(it.observedAt) == observedAt })
            assertEquals(start.toString(), body.startDate)
            assertEquals(end.toString(), body.endDate)
        }
    }

    @Test
    fun `a fresh window is answered from the store without a provider call`() {
        val warm = recgov()
        egress(listOf(warm), ttl = alwaysFresh) { post(request()) }
        val cold = recgov()
        egress(listOf(cold), ttl = alwaysFresh) {
            val response = post(request())
            assertEquals(HttpStatusCode.OK, response.status)
            val body = wire.decodeFromString(AvailabilityEgressResponseDto.serializer(), response.bodyAsText())
            assertEquals(0, cold.calls.size)
            assertEquals((WINDOW_DAYS * 2).toInt(), body.cells.size)
        }
    }

    @Test
    fun `refresh with a cutoff the stored cells meet answers from the store`() {
        egress(listOf(recgov())) { post(request()) }
        val cold = recgov()
        egress(listOf(cold), ttl = alwaysStale) {
            val response = post(request(freshAtOrAfter = observedAt.minus(Duration.ofDays(1)).toString()))
            assertEquals(HttpStatusCode.OK, response.status)
            val body = wire.decodeFromString(AvailabilityEgressResponseDto.serializer(), response.bodyAsText())
            assertEquals(0, cold.calls.size)
            assertTrue(body.fromCache)
        }
    }

    @Test
    fun `refresh with a cutoff newer than the stored cells fetches`() {
        egress(listOf(recgov())) { post(request()) }
        val again = recgov()
        egress(listOf(again), ttl = alwaysFresh) {
            val response = post(request(freshAtOrAfter = observedAt.plusSeconds(1).toString()))
            assertEquals(HttpStatusCode.OK, response.status)
            val body = wire.decodeFromString(AvailabilityEgressResponseDto.serializer(), response.bodyAsText())
            assertEquals(1, again.calls.size)
            assertEquals(false, body.fromCache)
        }
    }

    @Test
    fun `a malformed cutoff is 400 bad_date_window`() =
        egress(listOf(recgov())) {
            val response = post(request(freshAtOrAfter = "yesterday"))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("bad_date_window", errorOf(response.bodyAsText()))
        }

    @Test
    fun `a provider failure is 503 with its code`() =
        egress(listOf(recgov(onFailure = AvailabilityProviderError.RateLimited()))) {
            val response = post(request())
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("rate_limited", errorOf(response.bodyAsText()))
        }

    @Test
    fun `the route is absent unless enabled`() =
        egress(listOf(recgov()), enabled = false) {
            assertEquals(HttpStatusCode.NotFound, post(request()).status)
        }

    @Test
    fun `stored mode answers from the store without a provider call, however stale`() {
        egress(listOf(recgov())) { post(request()) }
        val cold = recgov()
        egress(listOf(cold), ttl = alwaysStale) {
            val response = post(request(mode = AvailabilityEgressMode.STORED))
            assertEquals(HttpStatusCode.OK, response.status)
            val body = wire.decodeFromString(AvailabilityEgressResponseDto.serializer(), response.bodyAsText())
            assertEquals(0, cold.calls.size)
            assertEquals((WINDOW_DAYS * 2).toInt(), body.cells.size)
            assertTrue(body.cells.all { Instant.parse(it.observedAt) == observedAt })
        }
    }

    @Test
    fun `stored mode with nothing stored returns no cells and calls nothing`() {
        val cold = recgov()
        egress(listOf(cold)) {
            val body =
                wire.decodeFromString(
                    AvailabilityEgressResponseDto.serializer(),
                    post(request(mode = AvailabilityEgressMode.STORED)).bodyAsText(),
                )
            assertEquals(emptyList(), body.cells)
            assertEquals(0, cold.calls.size)
        }
    }

    @Test
    fun `stored mode with a reversed window is 400`() =
        egress(listOf(recgov())) {
            assertEquals(
                HttpStatusCode.BadRequest,
                post(request(startDate = end, endDate = start, mode = AvailabilityEgressMode.STORED)).status,
            )
        }

    private fun storedBody(response: String) = wire.decodeFromString(AvailabilityEgressResponseDto.serializer(), response)

    @Test
    fun `stored mode lists the campground's campsites even with no cells, and its latest date`() =
        egress(listOf(recgov())) {
            val body = storedBody(post(request(mode = AvailabilityEgressMode.STORED)).bodyAsText())
            assertEquals(setOf(CAMPSITE_REF, SECOND_CAMPSITE_REF), body.campsites.map { it.dataProviderRef }.toSet())
            assertTrue(body.latestDate != null)
            assertEquals(emptyList(), body.cells)
        }

    @Test
    fun `stored mode resolves a missing window to the default week from the earliest date`() =
        egress(listOf(recgov())) {
            val body = storedBody(post(request(startDate = null, endDate = null, mode = AvailabilityEgressMode.STORED)).bodyAsText())
            assertEquals(DEFAULT_WEEK_DAYS, ChronoUnit.DAYS.between(LocalDate.parse(body.startDate), LocalDate.parse(body.endDate)))
        }

    @Test
    fun `stored mode refuses a window wider than the provider allows, naming the limit`() =
        egress(listOf(recgov())) {
            val response =
                post(request(endDate = start.plusDays(FAKE_PROVIDER_MAX_POLL_WINDOW_DAYS + 1L), mode = AvailabilityEgressMode.STORED))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = wire.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("window_too_long", error.getValue("error").jsonPrimitive.content)
            assertEquals(FAKE_PROVIDER_MAX_POLL_WINDOW_DAYS.toString(), error.getValue("max_days").jsonPrimitive.content)
        }

    @Test
    fun `stored mode refuses a start before the earliest date, naming it`() =
        egress(listOf(recgov())) {
            val response = post(request(startDate = start.minusDays(WINDOW_LEAD_DAYS + 1), mode = AvailabilityEgressMode.STORED))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(wire.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("earliest_date"))
        }
}
