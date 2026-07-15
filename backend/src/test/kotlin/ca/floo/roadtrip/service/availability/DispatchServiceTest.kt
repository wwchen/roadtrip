package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_VENDOR_RECGOV = "recgov"
private const val TEST_VENDOR_ASPIRA = "aspira"
private const val TEST_KIND_ATC = "atc"
private const val TEST_SIMULATE_RESULT_SUCCESS = "success"
private const val TEST_SIMULATE_RESULT_FAILURE = "failure"
private const val TEST_LEASE_SECONDS = 30L
private const val TEST_WAIT_SECONDS = 1L
private const val TEST_WATCH_ID = 42L
private const val TEST_STATUS_COMPLETED = "completed"
private const val TEST_STATUS_FAILED = "failed"
private const val TEST_ERROR_SIMULATED_FAILURE = "simulated_failure"
private const val TEST_FAILURE_DETAIL = "test dispatch requested failure"
private const val TEST_COMPANION_ID = "companion-A"
private const val TEST_RECGOV_FACILITY_ID = "100"
private const val TEST_RECGOV_CAMPSITE_ID = 7L
private const val TEST_RECGOV_VENDOR_ID = "site-7"
private const val TEST_CAMPSITE_LABEL = "Site 7"

class DispatchServiceTest {
    @Test
    fun `claim waits until a matching vendor dispatch is enqueued`() =
        runBlocking {
            val service = service()
            val claim =
                async {
                    service.claim(
                        selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                        wait = Duration.ofSeconds(TEST_WAIT_SECONDS),
                        lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                    )
                }
            delay(25)

            service.enqueueTestEvent(
                kind = TEST_KIND_ATC,
                vendor = TEST_VENDOR_RECGOV,
                simulateResult = TEST_SIMULATE_RESULT_SUCCESS,
                payloadVersion = null,
                payload = JsonObject(emptyMap()),
                watchId = null,
                stopWhenTriggered = false,
            )

            val claimed = withTimeout(Duration.ofSeconds(TEST_WAIT_SECONDS).toMillis()) { claim.await() }
            assertNotNull(claimed)
            assertEquals(TEST_VENDOR_RECGOV, claimed.vendor)
            assertEquals(TEST_SIMULATE_RESULT_SUCCESS, claimed.payload["simulate_result"]!!.jsonPrimitive.content)
        }

    @Test
    fun `claim selector respects vendor`() =
        runBlocking {
            val service = service()
            service.enqueueTestEvent(
                kind = TEST_KIND_ATC,
                vendor = TEST_VENDOR_RECGOV,
                simulateResult = TEST_SIMULATE_RESULT_SUCCESS,
                payloadVersion = null,
                payload = JsonObject(emptyMap()),
                watchId = null,
                stopWhenTriggered = false,
            )

            val aspiraClaim =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_ASPIRA)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )
            val recgovClaim =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )

            assertNull(aspiraClaim)
            assertNotNull(recgovClaim)
            assertEquals(TEST_VENDOR_RECGOV, recgovClaim.vendor)
        }

    @Test
    fun `enqueue atc sends offline slack when no companion waiter is connected`() =
        runBlocking {
            val slack = RecordingSlack()
            val service = service(slack = slack)

            service.enqueueAtc(fakeWatch(), listOf(opening(TEST_VENDOR_RECGOV)))

            assertEquals(1, slack.offlineAlerts.size)
            val alert = slack.offlineAlerts.single()
            assertEquals(TEST_WATCH_ID, alert.watchId)
            assertEquals(TEST_VENDOR_RECGOV, alert.vendor)
            assertEquals(1, alert.openings.size)
        }

    @Test
    fun `enqueue recgov add to cart queues claimable dispatch with booking target payload`() =
        runBlocking {
            val service = service()

            val result = service.enqueueRecGovAddToCart(addToCartRequest())

            assertTrue(result is AddToCartResult.Queued)
            assertEquals(BookingProviderId.RECGOV, result.providerId)
            val claimed =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )
            assertNotNull(claimed)
            assertEquals(result.dispatchId, claimed.id)
            assertEquals("atc.recgov.v1", claimed.payloadVersion)
            val payload = claimed.payload
            assertEquals(TEST_WATCH_ID.toString(), payload["watch_id"]!!.jsonPrimitive.content)
            assertEquals(TEST_VENDOR_RECGOV, payload["vendor"]!!.jsonPrimitive.content)
            assertEquals("2026-07-04", payload["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-05", payload["end_date"]!!.jsonPrimitive.content)
            val opening = payload["openings"]!!.jsonArray.single().jsonObject
            assertEquals(TEST_CAMPSITE_LABEL, opening["label"]!!.jsonPrimitive.content)
            assertEquals(TEST_RECGOV_CAMPSITE_ID.toString(), opening["campsite_id"]!!.jsonPrimitive.content)
            assertEquals(TEST_RECGOV_VENDOR_ID, opening["vendor_id"]!!.jsonPrimitive.content)
            assertEquals("https://example.test/book", opening["booking_url"]!!.jsonPrimitive.content)
        }

    @Test
    fun `complete marks a stop-when-triggered watch done`() =
        runBlocking {
            val completedWatches = mutableListOf<Long>()
            val service = service(watchCompletion = DispatchWatchCompletion { watchId -> completedWatches.add(watchId) })
            service.enqueueTestEvent(
                kind = TEST_KIND_ATC,
                vendor = TEST_VENDOR_RECGOV,
                simulateResult = TEST_SIMULATE_RESULT_SUCCESS,
                payloadVersion = null,
                payload = JsonObject(emptyMap()),
                watchId = TEST_WATCH_ID,
                stopWhenTriggered = true,
            )
            val claimed =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )
            assertNotNull(claimed)

            val result = service.complete(claimed.id, claimed.leaseToken, completeReport(claimed.leaseToken))

            assertTrue(result is DispatchCompleteOutcome.Completed)
            assertEquals(true, result.watchDone)
            assertEquals(listOf(TEST_WATCH_ID), completedWatches)
        }

    @Test
    fun `complete sends dispatch result slack with complete request`() =
        runBlocking {
            val slack = RecordingSlack()
            val service = service(slack = slack)
            service.enqueueTestEvent(
                kind = TEST_KIND_ATC,
                vendor = TEST_VENDOR_RECGOV,
                simulateResult = TEST_SIMULATE_RESULT_SUCCESS,
                payloadVersion = null,
                payload = JsonObject(emptyMap()),
                watchId = null,
                stopWhenTriggered = false,
            )
            val claimed =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )
            assertNotNull(claimed)
            val request = completeReport(claimed.leaseToken)

            val result = service.complete(claimed.id, claimed.leaseToken, request)

            assertTrue(result is DispatchCompleteOutcome.Completed)
            val notice = slack.dispatchResults.single()
            assertEquals(claimed.id, notice.dispatchId)
            assertEquals(TEST_KIND_ATC, notice.kind)
            assertEquals(TEST_VENDOR_RECGOV, notice.vendor)
            assertEquals(TEST_STATUS_COMPLETED, notice.status)
            assertEquals(request, notice.request)
        }

    @Test
    fun `fail sends dispatch result slack with fail request`() =
        runBlocking {
            val slack = RecordingSlack()
            val service = service(slack = slack)
            service.enqueueTestEvent(
                kind = TEST_KIND_ATC,
                vendor = TEST_VENDOR_RECGOV,
                simulateResult = TEST_SIMULATE_RESULT_FAILURE,
                payloadVersion = null,
                payload = JsonObject(emptyMap()),
                watchId = null,
                stopWhenTriggered = false,
            )
            val claimed =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )
            assertNotNull(claimed)
            val request = failReport(claimed.leaseToken)

            val result = service.fail(claimed.id, claimed.leaseToken, request)

            assertTrue(result is DispatchFailResult.Failed)
            val notice = slack.dispatchResults.single()
            assertEquals(claimed.id, notice.dispatchId)
            assertEquals(TEST_KIND_ATC, notice.kind)
            assertEquals(TEST_VENDOR_RECGOV, notice.vendor)
            assertEquals(TEST_STATUS_FAILED, notice.status)
            assertEquals(request, notice.request)
        }

    @Test
    fun `atc trigger handler queues dispatch and keeps watch completion with companion result`() =
        runBlocking {
            val port = RecordingAtcPort()
            val handler = AtcTriggerActionHandler(port)
            val delivered = handler.fire(fakeWatch(), listOf(opening(TEST_VENDOR_RECGOV)))

            assertFalse(delivered)
            assertEquals(TEST_WATCH_ID, port.watch?.id)
            assertEquals(TEST_VENDOR_RECGOV, port.openings.single().vendor)
        }

    private fun service(
        slack: SlackNotificationService = RecordingSlack(),
        watchCompletion: DispatchWatchCompletion = DispatchWatchCompletion { true },
    ): DispatchService =
        DispatchService(
            store = InMemoryDispatchStore(),
            waiters = DispatchWaiterRegistry(),
            slack = slack,
            watchCompletion = watchCompletion,
            clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC),
        )

    private class RecordingAtcPort : AtcDispatchPort {
        var watch: AvailabilityWatchRepo.Watch? = null
        var openings: List<WatchOpening> = emptyList()

        override suspend fun enqueueAtc(
            watch: AvailabilityWatchRepo.Watch,
            openings: List<WatchOpening>,
        ): List<DispatchQueued> {
            this.watch = watch
            this.openings = openings
            return emptyList()
        }
    }

    private class RecordingSlack : SlackNotificationService {
        data class OfflineAlert(
            val watchId: Long,
            val vendor: String,
            val openings: List<WatchOpening>,
            val channel: String?,
        )

        val offlineAlerts = mutableListOf<OfflineAlert>()
        val dispatchResults = mutableListOf<DispatchResult>()

        data class DispatchResult(
            val dispatchId: Long,
            val kind: String,
            val vendor: String,
            val payloadVersion: String,
            val status: String,
            val request: JsonObject,
            val channel: String?,
        )

        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            channel: String?,
        ): Boolean = true

        override suspend fun sendWatchOpenings(
            watchId: Long,
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            channel: String?,
            appRootUrl: String?,
        ): Boolean = true

        override suspend fun sendAtcCompanionOffline(
            watchId: Long,
            vendor: String,
            openings: List<WatchOpening>,
            channel: String?,
        ): Boolean {
            offlineAlerts += OfflineAlert(watchId, vendor, openings, channel)
            return true
        }

        override suspend fun sendDispatchResult(
            dispatchId: Long,
            kind: String,
            vendor: String,
            payloadVersion: String,
            status: String,
            request: JsonObject,
            channel: String?,
        ): Boolean {
            dispatchResults +=
                DispatchResult(
                    dispatchId = dispatchId,
                    kind = kind,
                    vendor = vendor,
                    payloadVersion = payloadVersion,
                    status = status,
                    request = request,
                    channel = channel,
                )
            return true
        }

        override suspend fun postResponseWatchStatus(
            responseUrl: String,
            notice: WatchStatusNotice,
        ): Boolean = true

        override suspend fun postResponseStaleWatch(
            responseUrl: String,
            watchId: Long,
        ): Boolean = true
    }

    private fun fakeWatch(): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = TEST_WATCH_ID,
            targets = emptyList<AvailabilityWatchTargetRepo.WatchTarget>(),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = null,
            triggerKinds = listOf(AtcTriggerActionHandler.KIND),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = true,
            status = WatchStatus.ACTIVE,
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )

    private fun opening(vendor: String): WatchOpening =
        WatchOpening(
            label = "Site 12",
            loop = "Loop A",
            siteType = "Tent",
            date = LocalDate.parse("2026-07-04"),
            campgroundId = 100L,
            campground = "Test CG",
            bookingUrl = "https://example.test/book",
            vendor = vendor,
        )

    private fun addToCartRequest(): AddToCartRequest =
        AddToCartRequest(
            watchId = TEST_WATCH_ID,
            target =
                BookingTarget(
                    providerId = BookingProviderId.RECGOV,
                    parentRef = ProviderRef.RecGov(TEST_RECGOV_FACILITY_ID),
                    campsiteRef = CatalogCampsiteRef(campsiteId = TEST_RECGOV_CAMPSITE_ID, vendorId = TEST_RECGOV_VENDOR_ID),
                ),
            arrivalDate = LocalDate.parse("2026-07-04"),
            checkoutDate = LocalDate.parse("2026-07-05"),
            campsiteLabel = TEST_CAMPSITE_LABEL,
            loop = "Loop A",
            siteType = "Tent",
            campgroundId = 100L,
            campgroundName = "Test CG",
            bookingUrl = "https://example.test/book",
            stopWhenTriggered = true,
        )

    private fun completeReport(leaseToken: String): JsonObject =
        buildJsonObject {
            put("lease_token", leaseToken)
            putJsonObject("result") {
                put("companion_id", TEST_COMPANION_ID)
                put("simulated", true)
                put("simulate_result", TEST_SIMULATE_RESULT_SUCCESS)
            }
        }

    private fun failReport(leaseToken: String): JsonObject =
        buildJsonObject {
            put("lease_token", leaseToken)
            put("error", TEST_ERROR_SIMULATED_FAILURE)
            put("detail", TEST_FAILURE_DETAIL)
            putJsonObject("result") {
                put("companion_id", TEST_COMPANION_ID)
                put("simulated", true)
                put("simulate_result", TEST_SIMULATE_RESULT_FAILURE)
            }
        }
}
