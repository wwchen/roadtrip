package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.settings.BookingCredentialsConfigured
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.RecGovProfileSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import org.slf4j.LoggerFactory

/** Where a site this adapter held actually is. Rec.gov's cart, and nobody else's. */
internal const val RECGOV_CART_URL = "https://www.recreation.gov/cart"

/** Rec.gov's own refusals: its codes, so they live with the adapter that reads them. */
internal object RecGovBookingCodes {
    /** Rec.gov offered the site but not a bookable confirmation control. */
    const val CONFIRMATION_DISABLED = "recgov_confirmation_disabled"

    /** Rec.gov's calendar refused the requested arrival date outright. */
    const val DATES_NOT_OFFERED = "recgov_dates_not_offered"

    /** Rec.gov showed the site but offered no Reserve/Add-to-Cart control. */
    const val NO_RESERVE_BUTTON = "recgov_no_reserve_button"
}

/** The profile could not be signed in unattended; only the user can fix it. */
internal const val RECGOV_SESSION_EXPIRED_ERROR = RecGovSessionCodes.SESSION_EXPIRED
internal const val RECGOV_SESSION_EXPIRED_DETAIL = "session expired — re-login in Settings"

/** The companion itself is broken; nothing the owner can do about it. */
internal const val COMPANION_ERROR_DETAIL = "the booking service hit an internal error"

internal class RecGovBookingAdapter(
    private val companionAtc: RecGovAtcExecutor,
    /**
     * Null only where the deployment has a companion but no credential
     * custodian — the preflight is then skipped rather than failing every hold.
     */
    private val session: RecGovProfileSessionPort? = null,
    /**
     * Told about every hold that reaches the browser, so the keepalive sweep can
     * stay off a profile the fire path is using. Null where there is no sweep.
     */
    private val recentFires: RecentAtcFires? = null,
    /** Whose cart a hold could land in. Null where no custodian is wired. */
    private val credentials: BookingCredentialsConfigured? = null,
) : BookingAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    private val hold = RecGovProfileHold(companionAtc)

    override val id: BookingProvider = BookingProvider.RECGOV

    override fun canFulfil(user: UserId): Boolean = credentials?.isConfigured(id, user) == true

    override fun targetFor(
        parentRef: BookingProviderRef,
        campsiteId: Long,
        vendorSiteId: String,
    ): BookingTarget? {
        if (parentRef !is BookingProviderRef.RecGov) return null
        return BookingTarget(
            providerId = id,
            parentRef = parentRef,
            campsiteId = campsiteId,
            vendorSiteId = vendorSiteId,
        )
    }

    override fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean =
        action == BookingAction.ADD_TO_CART &&
            target.providerId == id &&
            target.parentRef is BookingProviderRef.RecGov &&
            target.vendorSiteId.isNotBlank()

    override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        if (!can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        val owner = UserId(request.ownerUserId)
        val holdRequest =
            ProfileHoldRequest(
                profileId = profileIdFor(owner),
                vendorSiteId = request.target.vendorSiteId,
                arrival = request.arrivalDate,
                checkout = request.checkoutDate,
            )
        val payload = holdRequest.toAtcPayload()
        recentFires?.record(holdRequest.profileId)
        val relogin: (suspend (String?) -> ProfileHoldBlocker?)? =
            session?.takeIf { request.allowUnattendedRelogin }?.let { client -> { code -> reLogin(client, owner, code) } }
        return when (val outcome = hold.hold(session?.let { ownerSession(it, owner) }, holdRequest, relogin)) {
            is ProfileHoldOutcome.Held ->
                AddToCartResult.Completed(
                    providerId = id,
                    cartUrl = outcome.cartUrl,
                    request = payload,
                    response = outcome.response,
                )
            is ProfileHoldOutcome.Blocked ->
                AddToCartResult.Failed(
                    providerId = id,
                    error = outcome.code,
                    detail = outcome.detail,
                    category = outcome.category,
                    request = payload,
                    response = outcome.response,
                )
        }
    }

    private fun ownerSession(
        client: RecGovProfileSessionPort,
        owner: UserId,
    ): RecGovHoldSession =
        object : RecGovHoldSession {
            override suspend fun health(): CompanionSessionHealth = client.health(owner)

            override suspend fun refresh(): CompanionActionResult = client.refreshSession(owner)
        }

    private suspend fun reLogin(
        client: RecGovProfileSessionPort,
        owner: UserId,
        healthCode: String?,
    ): ProfileHoldBlocker? =
        when (val recovery = client.reLogin(owner)) {
            is CompanionActionResult.Ok -> {
                log.info("recgov session recovered unattended for owner user_id={}", owner.value)
                null
            }
            is CompanionActionResult.Failed -> {
                log.warn(
                    "unattended recgov re-login refused for owner user_id={} health={} code={} detail={}",
                    owner.value,
                    healthCode,
                    recovery.code,
                    recovery.detail,
                )
                ProfileHoldBlocker(recovery.code, recovery.detail)
            }
        }

    private fun profileIdFor(owner: UserId): String = session?.profileId(owner) ?: owner.value.toString()
}
