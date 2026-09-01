package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

internal class AtcTriggerActionHandler(
    private val bookings: BookingAdapterRegistry,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notifications: NotificationSender,
    private val targetResolver: WatchNotificationTargetResolver,
) : TriggerActionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kinds: Set<String> = setOf(KIND)

    /**
     * Who hears about this ATC.
     *
     * ATC carries the `atc` kind rather than `slack_notify`/`email_notify`, so
     * it resolves both targets directly instead of through the kind-gated
     * [WatchNotificationTargetResolver.resolve] — a user who opted into holding
     * a site has, by that act, asked to be told whether it worked.
     *
     * **Email is the one that usually lands.** The Slack target is fail-closed
     * on the owner having BOTH a personal token and a channel, so for most
     * owners it is null and the result would otherwise be announced to nobody.
     * Email resolves the same way watch openings do: the owner's
     * `notification_email`, falling back to their login email.
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
                reportResult(
                    watch = watch,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_COMPLETED,
                    request = result.request,
                    response = result.response,
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
                reportResult(
                    watch = watch,
                    vendor = result.providerId.vendorSlug(),
                    status = ATC_RESULT_FAILED,
                    request = result.request,
                    response = result.response,
                    // The reason travels as its own argument: a preflight
                    // failure has no companion response to carry it, and those
                    // are the failures the owner can actually act on.
                    error = result.error,
                    detail = result.detail,
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

    /**
     * Sends the outcome and says so when nobody heard it.
     *
     * The delivery result was previously discarded, which made "the hold
     * happened but the owner was never told" indistinguishable from a clean
     * run in the logs — the exact failure this whole path exists to prevent.
     */
    private suspend fun reportResult(
        watch: AvailabilityWatchRepo.Watch,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        error: String? = null,
        detail: String? = null,
    ) {
        val targets = atcTargets(watch)
        val delivered =
            notifications.sendAtcResult(
                watchId = watch.id,
                vendor = vendor,
                status = status,
                request = request,
                response = response,
                error = error,
                detail = detail,
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
