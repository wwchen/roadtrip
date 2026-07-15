package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.notification.SlackNotificationService
import org.slf4j.LoggerFactory

internal class AtcTriggerActionHandler(
    private val bookings: BookingProviderRegistry,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val slack: SlackNotificationService,
) : TriggerActionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kind: String = KIND

    override suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<TriggerOpening>,
    ): Boolean {
        val pending =
            openings.mapNotNull { opening ->
                addToCartRequest(watch, opening)?.let { PendingAddToCart(it, opening) }
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
                        "failed to enqueue ATC booking action for watch_id={} campsite_id={} date={}",
                        watch.id,
                        next.request.target.campsiteRef.campsiteId,
                        next.request.arrivalDate,
                        it,
                    )
                }.getOrNull()

        when (result) {
            is AddToCartResult.Queued -> {
                log.info(
                    "ATC dispatch queued: watch_id={} provider={} dispatch_id={} notified_waiters={}",
                    watch.id,
                    result.providerId,
                    result.dispatchId,
                    result.notifiedWaiters,
                )
                if (result.notifiedWaiters == 0) {
                    notifyOffline(watch, result.providerId.vendorSlug(), next.opening)
                }
            }
            AddToCartResult.Unsupported ->
                log.warn(
                    "ATC booking action unsupported for watch_id={} provider={} campsite_id={}",
                    watch.id,
                    next.request.target.providerId,
                    next.request.target.campsiteRef.campsiteId,
                )
            null -> Unit
        }
        return false
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

    private suspend fun notifyOffline(
        watch: AvailabilityWatchRepo.Watch,
        vendor: String,
        opening: TriggerOpening,
    ) {
        log.error(
            "ATC companion offline: watch_id={} vendor={} campsite_id={} date={}",
            watch.id,
            vendor,
            opening.campsite.id,
            opening.date,
        )
        slack.sendAtcCompanionOffline(
            watchId = watch.id,
            vendor = vendor,
            openings = listOf(opening.notification),
            channel = watch.channelOverride(),
        )
    }

    private data class PendingAddToCart(
        val request: AddToCartRequest,
        val opening: TriggerOpening,
    )

    private fun BookingProviderId.vendorSlug(): String = name.lowercase()

    companion object {
        const val KIND = AvailabilityTriggerKinds.ATC
    }
}
