package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
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
private const val TEST_DISPATCH_PAYLOAD_VALUE = "bar"

private val TEST_NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")

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

            enqueueDispatch(service)

            val claimed = withTimeout(Duration.ofSeconds(TEST_WAIT_SECONDS).toMillis()) { claim.await() }
            assertNotNull(claimed)
            assertEquals(TEST_VENDOR_RECGOV, claimed.vendor)
            assertEquals(TEST_SIMULATE_RESULT_SUCCESS, claimed.payload["simulate_result"]!!.jsonPrimitive.content)
        }

    @Test
    fun `claim selector respects vendor`() =
        runBlocking {
            val service = service()
            enqueueDispatch(service)

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
    fun `enqueue queues claimable dispatch envelope`() =
        runBlocking {
            val service = service()

            val result =
                service.enqueue(
                    DispatchCreateInput(
                        kind = TEST_KIND_ATC,
                        vendor = TEST_VENDOR_RECGOV,
                        payloadVersion = "atc.recgov.v1",
                        payload =
                            buildJsonObject {
                                put("foo", TEST_DISPATCH_PAYLOAD_VALUE)
                            },
                        watchId = TEST_WATCH_ID,
                        stopWhenTriggered = true,
                    ),
                )

            val claimed =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(TEST_LEASE_SECONDS),
                )
            assertNotNull(claimed)
            assertEquals(result.id, claimed.id)
            assertEquals("atc.recgov.v1", claimed.payloadVersion)
            assertEquals(TEST_DISPATCH_PAYLOAD_VALUE, claimed.payload["foo"]!!.jsonPrimitive.content)
        }

    @Test
    fun `enqueue uses configured pending ttl`() =
        runBlocking {
            val ttl = Duration.ofSeconds(5)
            val service = service(config = DispatchConfig(pendingTtl = ttl))

            val queued = enqueueDispatch(service)

            assertEquals(TEST_NOW.plus(ttl), queued.expiresAt)
        }

    @Test
    fun `claim uses configured default and max lease durations`() =
        runBlocking {
            val service =
                service(
                    config =
                        DispatchConfig(
                            defaultLease = Duration.ofSeconds(7),
                            maxLease = Duration.ofSeconds(11),
                        ),
                )
            enqueueDispatch(service)
            enqueueDispatch(service)

            val defaultLeaseClaim =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = null,
                )
            val maxLeaseClaim =
                service.claim(
                    selector = DispatchClaimSelector.of(TEST_KIND_ATC, listOf(TEST_VENDOR_RECGOV)),
                    wait = Duration.ZERO,
                    lease = Duration.ofSeconds(99),
                )

            assertNotNull(defaultLeaseClaim)
            assertNotNull(maxLeaseClaim)
            assertEquals(TEST_NOW.plusSeconds(7), defaultLeaseClaim.leaseExpiresAt)
            assertEquals(TEST_NOW.plusSeconds(11), maxLeaseClaim.leaseExpiresAt)
        }

    @Test
    fun `complete marks a stop-when-triggered watch done`() =
        runBlocking {
            val completedWatches = mutableListOf<Long>()
            val service = service(watchCompletion = DispatchWatchCompletion { watchId -> completedWatches.add(watchId) })
            enqueueDispatch(service, watchId = TEST_WATCH_ID, stopWhenTriggered = true)
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
            enqueueDispatch(service)
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
            enqueueDispatch(service, simulateResult = TEST_SIMULATE_RESULT_FAILURE)
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

    private fun service(
        slack: SlackNotificationService = RecordingSlack(),
        watchCompletion: DispatchWatchCompletion = DispatchWatchCompletion { true },
        config: DispatchConfig = DispatchConfig(),
    ): DispatchService =
        DispatchService(
            store = InMemoryDispatchStore(),
            waiters = DispatchWaiterRegistry(),
            slack = slack,
            watchCompletion = watchCompletion,
            config = config,
            clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
        )

    private suspend fun enqueueDispatch(
        service: DispatchService,
        simulateResult: String = TEST_SIMULATE_RESULT_SUCCESS,
        watchId: Long? = null,
        stopWhenTriggered: Boolean = false,
    ): DispatchQueued =
        service.enqueue(
            DispatchCreateInput(
                kind = TEST_KIND_ATC,
                vendor = TEST_VENDOR_RECGOV,
                payloadVersion = "atc.recgov.v1",
                payload =
                    buildJsonObject {
                        put("simulate_result", simulateResult)
                    },
                watchId = watchId,
                stopWhenTriggered = stopWhenTriggered,
            ),
        )

    private class RecordingSlack : SlackNotificationService {
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
