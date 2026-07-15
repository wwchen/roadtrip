package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovAddToCartDispatchPort
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

private const val DEFAULT_PENDING_TTL_SECONDS = 30L
private const val MAX_CLAIM_WAIT_SECONDS = 30L
private const val MIN_CLAIM_WAIT_MILLIS = 1L
private const val DEFAULT_LEASE_SECONDS = 30L
private const val MIN_LEASE_SECONDS = 1L
private const val MAX_LEASE_SECONDS = 120L
private const val PAYLOAD_VERSION_SUFFIX = "v1"
private const val UNKNOWN_VENDOR = "unknown"
private const val TEST_OPENING_LABEL = "Companion Test Site"
private const val TEST_OPENING_CAMPGROUND = "Companion Test Campground"
private const val TEST_BOOKING_URL = "https://example.invalid/companion-test"
private const val TEST_WATCH_ID = 0L
private const val TEST_WINDOW_DAYS = 1L
private const val DISPATCH_RESULT_COMPLETED = "completed"
private const val DISPATCH_RESULT_FAILED = "failed"
private const val RECGOV_VENDOR = "recgov"

internal fun interface DispatchWatchCompletion {
    fun markDone(watchId: Long): Boolean
}

internal interface AtcDispatchPort {
    suspend fun enqueueAtc(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<WatchOpening>,
    ): List<DispatchQueued>
}

internal class DispatchService(
    private val store: DispatchStore,
    private val waiters: DispatchWaiterRegistry,
    private val slack: SlackNotificationService,
    private val watchCompletion: DispatchWatchCompletion,
    private val clock: Clock = Clock.systemUTC(),
    private val pendingTtl: Duration = Duration.ofSeconds(DEFAULT_PENDING_TTL_SECONDS),
) : AtcDispatchPort,
    RecGovAddToCartDispatchPort {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun claim(
        selector: DispatchClaimSelector,
        wait: Duration,
        lease: Duration,
    ): DispatchClaimed? {
        val leaseDuration = normalizeLease(lease)
        store.claim(selector, leaseDuration, now())?.let { return it }
        val waitDuration = normalizeWait(wait)
        if (waitDuration.isZero) return null

        val registration = waiters.register(selector)
        try {
            store.claim(selector, leaseDuration, now())?.let { return it }
            withTimeoutOrNull(waitDuration.toMillis().coerceAtLeast(MIN_CLAIM_WAIT_MILLIS)) {
                registration.await()
            }
            return store.claim(selector, leaseDuration, now())
        } finally {
            registration.close()
        }
    }

    fun heartbeat(
        id: Long,
        leaseToken: String,
        lease: Duration,
    ): DispatchLeaseResult = store.heartbeat(id, leaseToken, normalizeLease(lease), now())

    suspend fun complete(
        id: Long,
        leaseToken: String,
        request: JsonObject,
    ): DispatchCompleteOutcome =
        when (val result = store.complete(id, leaseToken, now())) {
            is DispatchCompleteResult.Completed -> {
                val dispatch = result.dispatch
                slack.sendDispatchResult(
                    dispatchId = dispatch.id,
                    kind = dispatch.kind,
                    vendor = dispatch.vendor,
                    payloadVersion = dispatch.payloadVersion,
                    status = DISPATCH_RESULT_COMPLETED,
                    request = request,
                )
                val watchDone =
                    dispatch.watchId
                        ?.takeIf { dispatch.stopWhenTriggered }
                        ?.let { watchCompletion.markDone(it) }
                DispatchCompleteOutcome.Completed(id = dispatch.id, watchDone = watchDone)
            }
            DispatchCompleteResult.InvalidLease -> DispatchCompleteOutcome.InvalidLease
            DispatchCompleteResult.NotFound -> DispatchCompleteOutcome.NotFound
        }

    suspend fun fail(
        id: Long,
        leaseToken: String,
        request: JsonObject,
    ): DispatchFailResult =
        when (val result = store.fail(id, leaseToken, now())) {
            is DispatchFailResult.Failed -> {
                val dispatch = result.dispatch
                slack.sendDispatchResult(
                    dispatchId = dispatch.id,
                    kind = dispatch.kind,
                    vendor = dispatch.vendor,
                    payloadVersion = dispatch.payloadVersion,
                    status = DISPATCH_RESULT_FAILED,
                    request = request,
                )
                result
            }
            DispatchFailResult.InvalidLease -> DispatchFailResult.InvalidLease
            DispatchFailResult.NotFound -> DispatchFailResult.NotFound
        }

    override suspend fun enqueueAtc(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<WatchOpening>,
    ): List<DispatchQueued> =
        openings
            .groupBy { opening -> normalizeDispatchKey(opening.vendor ?: UNKNOWN_VENDOR) }
            .map { (vendor, vendorOpenings) ->
                enqueue(
                    input =
                        DispatchCreateInput(
                            kind = AtcTriggerActionHandler.KIND,
                            vendor = vendor,
                            payloadVersion = payloadVersion(AtcTriggerActionHandler.KIND, vendor),
                            payload = atcPayload(watch, vendor, vendorOpenings),
                            watchId = watch.id,
                            stopWhenTriggered = watch.stopWhenTriggered,
                        ),
                    offlineAlert = OfflineAlert.Atc(watch = watch, openings = vendorOpenings),
                )
            }

    override suspend fun enqueueRecGovAddToCart(request: AddToCartRequest): AddToCartResult {
        val version = payloadVersion(AtcTriggerActionHandler.KIND, RECGOV_VENDOR)
        val queued =
            enqueue(
                input =
                    DispatchCreateInput(
                        kind = AtcTriggerActionHandler.KIND,
                        vendor = RECGOV_VENDOR,
                        payloadVersion = version,
                        payload = addToCartPayload(request, RECGOV_VENDOR, version),
                        watchId = request.watchId,
                        stopWhenTriggered = request.stopWhenTriggered,
                    ),
                offlineAlert = OfflineAlert.None,
            )
        return AddToCartResult.Queued(
            dispatchId = queued.id,
            providerId = BookingProviderId.RECGOV,
            notifiedWaiters = queued.notifiedWaiters,
        )
    }

    suspend fun enqueueTestEvent(
        kind: String,
        vendor: String,
        simulateResult: String,
        payloadVersion: String?,
        payload: JsonObject,
        watchId: Long?,
        stopWhenTriggered: Boolean,
    ): DispatchQueued {
        val normalizedKind = normalizeDispatchKey(kind)
        val normalizedVendor = normalizeDispatchKey(vendor)
        val version = payloadVersion?.let(::normalizeDispatchKey) ?: payloadVersion(AtcTriggerActionHandler.KIND, normalizedVendor)
        return enqueue(
            input =
                DispatchCreateInput(
                    kind = normalizedKind,
                    vendor = normalizedVendor,
                    payloadVersion = version,
                    payload = testPayload(normalizedVendor, version, simulateResult, payload, watchId),
                    watchId = watchId,
                    stopWhenTriggered = stopWhenTriggered,
                ),
            offlineAlert = OfflineAlert.None,
        )
    }

    private suspend fun enqueue(
        input: DispatchCreateInput,
        offlineAlert: OfflineAlert,
    ): DispatchQueued {
        val queued = store.enqueue(input, pendingTtl, now())
        val notifiedWaiters = waiters.notifyMatching(queued)
        val notified = queued.copy(notifiedWaiters = notifiedWaiters)
        if (notifiedWaiters == 0 && offlineAlert is OfflineAlert.Atc) {
            log.error(
                "ATC companion offline: watch_id={} vendor={} dispatch_id={} openings={}",
                offlineAlert.watch.id,
                input.vendor,
                queued.id,
                offlineAlert.openings.size,
            )
            slack.sendAtcCompanionOffline(
                watchId = offlineAlert.watch.id,
                vendor = input.vendor,
                openings = offlineAlert.openings,
                channel = offlineAlert.watch.channelOverride(),
            )
        }
        return notified
    }

    private fun atcPayload(
        watch: AvailabilityWatchRepo.Watch,
        vendor: String,
        openings: List<WatchOpening>,
    ): JsonObject {
        val version = payloadVersion(AtcTriggerActionHandler.KIND, vendor)
        return buildJsonObject {
            put("watch_id", watch.id)
            put("vendor", vendor)
            put("payload_version", version)
            put("start_date", watch.startDate.toString())
            put("end_date", watch.endDate.toString())
            putJsonArray("openings") {
                openings.forEach { opening -> add(opening.toPayload(vendor)) }
            }
        }
    }

    private fun testPayload(
        vendor: String,
        payloadVersion: String,
        simulateResult: String,
        payload: JsonObject,
        watchId: Long?,
    ): JsonObject {
        val startDate = LocalDate.ofInstant(now(), ZoneOffset.UTC)
        val endDate = startDate.plusDays(TEST_WINDOW_DAYS)
        return buildJsonObject {
            put("watch_id", watchId ?: TEST_WATCH_ID)
            put("vendor", vendor)
            put("payload_version", payloadVersion)
            put("start_date", startDate.toString())
            put("end_date", endDate.toString())
            putJsonArray("openings") {
                add(
                    WatchOpening(
                        label = TEST_OPENING_LABEL,
                        loop = null,
                        siteType = "test",
                        date = startDate,
                        campgroundId = null,
                        campground = TEST_OPENING_CAMPGROUND,
                        bookingUrl = TEST_BOOKING_URL,
                        vendor = vendor,
                    ).toPayload(vendor),
                )
            }
            put("simulate_result", normalizeDispatchKey(simulateResult))
            if (payload.isNotEmpty()) put("data", payload)
        }
    }

    private fun addToCartPayload(
        request: AddToCartRequest,
        vendor: String,
        payloadVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("watch_id", request.watchId)
            put("vendor", vendor)
            put("payload_version", payloadVersion)
            put("start_date", request.arrivalDate.toString())
            put("end_date", request.checkoutDate.toString())
            putJsonArray("openings") {
                add(request.toPayload(vendor))
            }
        }

    private fun WatchOpening.toPayload(vendor: String): JsonObject =
        buildJsonObject {
            put("label", label)
            put("date", date.toString())
            put("vendor", vendor)
            loop?.let { put("loop", it) }
            siteType?.let { put("site_type", it) }
            campgroundId?.let { put("campground_id", it) }
            campground?.let { put("campground", it) }
            bookingUrl?.let { put("booking_url", it) }
        }

    private fun AddToCartRequest.toPayload(vendor: String): JsonObject =
        buildJsonObject {
            put("label", campsiteLabel)
            put("date", arrivalDate.toString())
            put("vendor", vendor)
            put("campsite_id", target.campsiteRef.campsiteId)
            put("vendor_id", target.campsiteRef.vendorId)
            target.campsiteRef.mapId?.let { put("map_id", it) }
            target.campsiteRef.resourceLocationId?.let { put("resource_location_id", it) }
            loop?.let { put("loop", it) }
            siteType?.let { put("site_type", it) }
            campgroundId?.let { put("campground_id", it) }
            campgroundName?.let { put("campground", it) }
            bookingUrl?.let { put("booking_url", it) }
        }

    private fun payloadVersion(
        kind: String,
        vendor: String,
    ): String = "${normalizeDispatchKey(kind)}.${normalizeDispatchKey(vendor)}.$PAYLOAD_VERSION_SUFFIX"

    private fun normalizeWait(wait: Duration): Duration =
        when {
            wait.isNegative || wait.isZero -> Duration.ZERO
            wait.seconds > MAX_CLAIM_WAIT_SECONDS -> Duration.ofSeconds(MAX_CLAIM_WAIT_SECONDS)
            else -> wait
        }

    private fun normalizeLease(lease: Duration): Duration =
        when {
            lease.seconds < MIN_LEASE_SECONDS -> Duration.ofSeconds(DEFAULT_LEASE_SECONDS)
            lease.seconds > MAX_LEASE_SECONDS -> Duration.ofSeconds(MAX_LEASE_SECONDS)
            else -> lease
        }

    private fun now() = clock.instant()

    private sealed class OfflineAlert {
        data object None : OfflineAlert()

        data class Atc(
            val watch: AvailabilityWatchRepo.Watch,
            val openings: List<WatchOpening>,
        ) : OfflineAlert()
    }
}

internal sealed class DispatchCompleteOutcome {
    data class Completed(
        val id: Long,
        val watchDone: Boolean?,
    ) : DispatchCompleteOutcome()

    data object NotFound : DispatchCompleteOutcome()

    data object InvalidLease : DispatchCompleteOutcome()
}
