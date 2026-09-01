package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import org.slf4j.LoggerFactory

internal class AtcTriggerActionHandler(
    private val bookings: BookingAdapterRegistry,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notifications: NotificationSender,
    private val targetResolver: WatchNotificationTargetResolver,
) : TriggerActionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kinds: Set<String> = setOf(KIND)

    /** ATC only notifies Slack, and carries the `atc` kind rather than
     *  `slack_notify`, so it takes the owner-scoped Slack target directly (not via
     *  the kind-gated [WatchNotificationTargetResolver.resolve]). Empty when the
     *  owner has no owner-controlled channel — the alert simply doesn't fire, and
     *  never falls back to the shared default. */
    private fun slackTargets(watch: AvailabilityWatchRepo.Watch): List<NotificationTarget> =
        listOfNotNull(targetResolver.resolveSlackTarget(watch))

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
            return false
        }
        if (pending.size > 1) {
            log.info("ATC trigger selected first supported opening for watch_id={} supported_openings={}", watch.id, pending.size)
        }

        val next = pending.first()
        val result =
            runCatching { bookings.addToCart(next.request) }
                .onFailure {
                    log.error(
                        "failed to execute ATC booking action for watch_id={} campsite_id={} date={}",
                        watch.id,
                        next.request.target.campsiteId,
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
                    next.request.target.campsiteId,
                    next.request.arrivalDate,
                )
                notifications.sendAtcResult(
                    watchId = watch.id,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_COMPLETED,
                    request = result.request,
                    response = result.response,
                    targets = slackTargets(watch),
                )
                true
            }
            is AddToCartResult.Failed -> {
                log.warn(
                    "ATC failed: watch_id={} provider={} campsite_id={} date={} error={} detail={}",
                    watch.id,
                    result.providerId,
                    next.request.target.campsiteId,
                    next.request.arrivalDate,
                    result.error,
                    result.detail,
                )
                notifications.sendAtcResult(
                    watchId = watch.id,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_FAILED,
                    request = result.request,
                    response = result.response,
                    targets = slackTargets(watch),
                )
                false
            }
            AddToCartResult.Unsupported -> {
                log.warn(
                    "ATC booking action unsupported for watch_id={} provider={} campsite_id={}",
                    watch.id,
                    next.request.target.providerId,
                    next.request.target.campsiteId,
                )
                false
            }
            null -> false
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
            watchId = watch.id,
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
        private const val ATC_RESULT_COMPLETED = "completed"
        private const val ATC_RESULT_FAILED = "failed"
    }
}
