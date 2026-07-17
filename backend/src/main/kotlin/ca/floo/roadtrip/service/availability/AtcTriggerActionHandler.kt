package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import org.slf4j.LoggerFactory

internal class AtcTriggerActionHandler(
    private val bookings: BookingProviderRegistry,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notifications: NotificationSender,
) : TriggerActionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kinds: Set<String> = setOf(KIND)

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
                        next.request.target.campsiteRef.campsiteId,
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
                    next.request.target.campsiteRef.campsiteId,
                    next.request.arrivalDate,
                )
                notifications.sendAtcResult(
                    watchId = watch.id,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_COMPLETED,
                    request = result.request,
                    response = result.response,
                    targets = listOf(NotificationTarget.Slack(channel = watch.channelOverride())),
                )
                true
            }
            is AddToCartResult.Failed -> {
                log.warn(
                    "ATC failed: watch_id={} provider={} campsite_id={} date={} error={} detail={}",
                    watch.id,
                    result.providerId,
                    next.request.target.campsiteRef.campsiteId,
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
                    targets = listOf(NotificationTarget.Slack(channel = watch.channelOverride())),
                )
                false
            }
            AddToCartResult.Unsupported -> {
                log.warn(
                    "ATC booking action unsupported for watch_id={} provider={} campsite_id={}",
                    watch.id,
                    next.request.target.providerId,
                    next.request.target.campsiteRef.campsiteId,
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
        val notification = opening.notification
        return AddToCartRequest(
            watchId = watch.id,
            target = target,
            arrivalDate = opening.date,
            checkoutDate = watch.endDate,
            campsiteLabel = notification.label,
            loop = notification.loop,
            siteType = notification.siteType,
            campgroundId = notification.campgroundId,
            campgroundName = notification.campground,
            bookingUrl = notification.bookingUrl,
            stopWhenTriggered = watch.stopWhenTriggered,
        )
    }

    private data class PendingAddToCart(
        val request: AddToCartRequest,
    )

    private fun BookingProviderId.vendorSlug(): String = name.lowercase()

    companion object {
        const val KIND = AvailabilityTriggerKinds.ATC
        private const val ATC_RESULT_COMPLETED = "completed"
        private const val ATC_RESULT_FAILED = "failed"
    }
}
