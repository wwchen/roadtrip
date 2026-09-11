package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.observability.AtcOutcome
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.booking.BookingActionCodes
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.notification.common.AtcResultNotice
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.support.runCatchingCancellable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

internal class AtcTriggerActionHandler(
    private val bookings: BookingAdapterRegistry,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notifications: NotificationSender,
    private val targetResolver: WatchNotificationTargetResolver,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
) : TriggerActionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kinds: Set<String> = setOf(KIND)

    /**
     * Who hears about this ATC: both targets directly, not through the kind-gated
     * [WatchNotificationTargetResolver.resolve], since `atc` is its own kind.
     * Slack is fail-closed on a token and a channel, so email usually carries it.
     */
    private fun atcTargets(watch: AvailabilityWatchRepo.Watch): List<NotificationTarget> =
        listOfNotNull(
            targetResolver.resolveSlackTarget(watch),
            targetResolver.resolveEmailTarget(watch),
        )

    override suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<TriggerOpening>,
    ): Boolean {
        val pending =
            openings.mapNotNull { opening ->
                addToCartRequest(watch, opening)?.let { PendingAddToCart(it) }
            }
        if (pending.isEmpty()) {
            log.warn("ATC trigger unsupported for watch_id={} openings={}", watch.id, openings.size)
            val opening = openings.firstOrNull()
            // No booking provider was reached: the report falls back to the
            // vendor the owner saw the opening under, the metric to none.
            reportResult(
                watch = watch,
                vendor = opening?.watchOpening?.vendor,
                status = ATC_RESULT_FAILED,
                request = noCompanionRequest,
                response = null,
                error = BookingActionCodes.UNSUPPORTED_TARGET,
                detail = NO_TARGET_DETAIL,
            )
            metrics.atcFired(null, AtcOutcome.NO_TARGET)
            return false
        }
        if (pending.size > 1) {
            log.info("ATC trigger selected first supported opening for watch_id={} supported_openings={}", watch.id, pending.size)
        }

        val next = pending.first()
        val nextTarget = next.request.target
        // What the delivered report calls this vendor. The adapter that would
        // hold the site is the only layer that knows, and it knows on every
        // exit below — including the ones where the hold never happened.
        val bookingSystem = bookings.adapterFor(nextTarget)?.displayName
        val startedAt = System.nanoTime()
        val result =
            runCatchingCancellable { bookings.addToCart(next.request) }
                .onFailure {
                    log.error(
                        "failed to execute ATC booking action for watch_id={} campsite_id={} date={}",
                        watch.id,
                        nextTarget.campsiteId,
                        next.request.arrivalDate,
                        it,
                    )
                }.getOrNull()

        return when (result) {
            is AddToCartResult.Completed -> {
                log.info(
                    "ATC completed: watch_id={} provider={} campsite_id={} date={}",
                    watch.id,
                    result.providerId,
                    nextTarget.campsiteId,
                    next.request.arrivalDate,
                )
                reportResult(
                    watch = watch,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_COMPLETED,
                    request = result.request,
                    response = result.response,
                    bookingSystem = bookingSystem,
                    cartUrl = result.cartUrl,
                )
                metrics.atcFired(result.providerId, AtcOutcome.HELD, durationMs = elapsedMsSince(startedAt))
                true
            }
            is AddToCartResult.Failed -> {
                log.warn(
                    "ATC failed: watch_id={} provider={} campsite_id={} date={} error={} detail={}",
                    watch.id,
                    result.providerId,
                    nextTarget.campsiteId,
                    next.request.arrivalDate,
                    result.error,
                    result.detail,
                )
                reportResult(
                    watch = watch,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_FAILED,
                    request = result.request,
                    response = result.response,
                    bookingSystem = bookingSystem,
                    // The reason travels as its own argument: a preflight
                    // failure has no companion response to carry it, and those
                    // are the failures the owner can actually act on.
                    error = result.error,
                    detail = result.detail,
                )
                metrics.atcFired(result.providerId, AtcOutcome.FAILED, result.error, elapsedMsSince(startedAt))
                false
            }
            AddToCartResult.Unsupported -> {
                log.warn(
                    "ATC booking action unsupported for watch_id={} provider={} campsite_id={}",
                    watch.id,
                    nextTarget.providerId,
                    nextTarget.campsiteId,
                )
                reportResult(
                    watch = watch,
                    vendor = nextTarget.providerId.vendorSlug(),
                    status = ATC_RESULT_FAILED,
                    request = noCompanionRequest,
                    response = null,
                    bookingSystem = bookingSystem,
                    error = BookingActionCodes.UNSUPPORTED_TARGET,
                    detail = UNSUPPORTED_DETAIL,
                )
                metrics.atcFired(nextTarget.providerId, AtcOutcome.UNSUPPORTED, durationMs = elapsedMsSince(startedAt))
                false
            }
            null -> {
                // The throwable is already logged above with its stack; the
                // owner gets a reason they can read instead of internal wording.
                reportResult(
                    watch = watch,
                    vendor = nextTarget.providerId.vendorSlug(),
                    status = ATC_RESULT_FAILED,
                    request = noCompanionRequest,
                    response = null,
                    bookingSystem = bookingSystem,
                    error = BookingActionCodes.ATC_EXCEPTION,
                    detail = ATC_EXCEPTION_DETAIL,
                )
                metrics.atcFired(nextTarget.providerId, AtcOutcome.EXCEPTION, durationMs = elapsedMsSince(startedAt))
                false
            }
        }
    }

    private fun elapsedMsSince(startedAtNanos: Long): Int = ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI).toInt()

    /**
     * Sends the outcome and logs when nobody heard it — "held but never told"
     * used to look like a clean run. [request]/[response] are the booking
     * provider's; an exit that never reached it passes [noCompanionRequest] and
     * a null response, with the reason in [error]/[detail].
     */
    private suspend fun reportResult(
        watch: AvailabilityWatchRepo.Watch,
        vendor: String?,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        bookingSystem: String? = null,
        cartUrl: String? = null,
        error: String? = null,
        detail: String? = null,
    ) {
        val targets = atcTargets(watch)
        val delivered =
            notifications.sendAtcResult(
                AtcResultNotice(
                    watchId = watch.id,
                    vendor = vendor,
                    status = status,
                    request = request,
                    response = response,
                    bookingSystem = bookingSystem,
                    cartUrl = cartUrl,
                    error = error,
                    detail = detail,
                ),
                targets = targets,
            )
        // The fanout is all-or-nothing per target, so this covers both "nobody
        // heard" and "one channel of two failed" — either way somebody who
        // should know about this hold does not.
        if (!delivered) {
            log.warn(
                "ATC result for watch_id={} status={}: at least one notification target failed (targets={})",
                watch.id,
                status,
                targets.size,
            )
        }
    }

    private fun addToCartRequest(
        watch: AvailabilityWatchRepo.Watch,
        opening: TriggerOpening,
    ): AddToCartRequest? {
        val resolved = opening.resolvedTarget ?: return null
        val target = bookingTargets.targetFor(BookingAction.ADD_TO_CART, resolved) ?: return null
        val watchOpening = opening.watchOpening
        return AddToCartRequest(
            ownerUserId = watch.ownerUserId,
            target = target,
            arrivalDate = opening.date,
            checkoutDate = watch.endDate,
            campsiteLabel = watchOpening.label,
            loop = watchOpening.loop,
            siteType = watchOpening.siteType,
            campgroundId = watchOpening.campgroundId,
            campgroundName = watchOpening.campground,
            bookingUrl = watchOpening.bookingUrl,
            stopWhenTriggered = watch.stopWhenTriggered,
        )
    }

    private data class PendingAddToCart(
        val request: AddToCartRequest,
    )

    private fun BookingProvider.vendorSlug(): String = id

    companion object {
        const val KIND = AvailabilityTriggerKinds.ATC
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val ATC_RESULT_COMPLETED = "completed"

        /**
         * Held or not held — the only distinction either renderer draws from
         * status. Every cause that is not a vendor refusal travels in
         * `error`/`detail` instead, so a third status value would be a token
         * both renderers handle only by falling through.
         */
        private const val ATC_RESULT_FAILED = "failed"

        /** Nothing was sent, so there is no payload to show. */
        private val noCompanionRequest = JsonObject(emptyMap())

        private const val NO_TARGET_DETAIL =
            "no bookable site could be resolved for this opening — the campground's booking details " +
                "may have changed since the watch was saved"

        private const val UNSUPPORTED_DETAIL = "the booking provider for this campground cannot hold sites"

        private const val ATC_EXCEPTION_DETAIL = "an unexpected error stopped the hold — nothing you did"
    }
}
