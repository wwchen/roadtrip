package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.RecGovProfileSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import ca.floo.roadtrip.support.runCatchingCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private const val ERROR_COMPANION_EXCEPTION = "companion_exception"

/** The profile could not be signed in unattended; only the user can fix it. */
internal const val RECGOV_SESSION_EXPIRED_ERROR = RecGovSessionCodes.SESSION_EXPIRED
internal const val RECGOV_SESSION_EXPIRED_DETAIL = "session expired — re-login in Settings"

/** The companion itself is broken; nothing the owner can do about it. */
internal const val COMPANION_ERROR_DETAIL = "the booking service hit an internal error"

private const val FIELD_PROFILE_ID = "profile_id"

/**
 * Every companion code this adapter can surface, and who has to act on it.
 *
 * This table is the whole reason the layers above can stay vendor-agnostic: the
 * route used to keep two sets of these codes itself, and the recovery path used
 * to erase them all into `recgov_session_expired` — which told a user who had
 * just removed their credentials, or whose companion was down, to "re-login in
 * Settings". Anything absent here is [BookingFailureCategory.UPSTREAM], which is
 * the safe default: an unfamiliar failure is ours until someone classifies it.
 */
private val failureCategories: Map<String, BookingFailureCategory> =
    buildMap {
        // The caller signs in, saves credentials, or completes a challenge.
        listOf(
            RecGovSessionCodes.SESSION_EXPIRED,
            RecGovSessionCodes.SESSION_LAPSED,
            RecGovSessionCodes.SPA_LOGGED_OUT,
            RecGovSessionCodes.REFRESH_FAILED,
            RecGovSessionCodes.COMPANION_LOGIN_FAILED,
            RecGovSessionCodes.LOGIN_FAILED,
            RecGovSessionCodes.NOT_AUTHENTICATED,
            RecGovSessionCodes.NOT_CONFIGURED,
            RecGovSessionCodes.MFA_REQUIRED,
        ).forEach { put(it, BookingFailureCategory.CALLER_ACTION) }

        // Nothing is broken: something else holds the profile, the pool is full,
        // rec.gov is throttling us, or the site went in the seconds it took to
        // drive the browser.
        listOf(
            RecGovSessionCodes.PROFILE_BUSY,
            RecGovSessionCodes.BROWSER_CAP_REACHED,
            RecGovSessionCodes.LOGIN_BACKOFF,
            RecGovSessionCodes.CAPTCHA_REQUIRED,
            BookingActionCodes.CART_NOT_ADDED,
            BookingActionCodes.CONFIRMATION_DISABLED,
            BookingActionCodes.DATES_NOT_OFFERED,
            BookingActionCodes.NO_RESERVE_BUTTON,
        ).forEach { put(it, BookingFailureCategory.RETRY_LATER) }
    }

private fun categoryOf(code: String): BookingFailureCategory = failureCategories[code] ?: BookingFailureCategory.UPSTREAM

/**
 * A sentence for a refusal the companion did not explain.
 *
 * The owner's email renders `detail ?: error`, so a companion answer with no
 * `detail` used to reach them as the bare word `mfa_required`. One line per
 * category rather than one per code: the copy has to stay true for every member
 * of its category, and what the owner can actually do about it is a
 * category-level fact. The code rides along because it is the one thing that
 * makes a support report actionable — the same bargain `settings-errors.ts`
 * strikes for an unmapped code.
 */
private fun undetailed(
    code: String,
    category: BookingFailureCategory,
): String =
    when (category) {
        BookingFailureCategory.CALLER_ACTION -> "$code — this needs your attention in Settings"
        BookingFailureCategory.RETRY_LATER -> "$code — the hold could not be made this time"
        BookingFailureCategory.UPSTREAM -> COMPANION_ERROR_DETAIL
    }

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
) : BookingAdapter {
    private val log = LoggerFactory.getLogger(javaClass)

    override val id: BookingProvider = BookingProvider.RECGOV

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
        val profileId = profileIdFor(owner)
        val payload = request.toAtcPayload(profileId)
        // Claimed before the preflight, not just before the POST: a preflight
        // that has to refresh or re-log-in drives a browser behind the same
        // per-profile lock. The whole run is the stretch the keepalive stays out
        // of, and a fire that ends at the preflight costs it one skipped refresh.
        recentFires?.record(profileId)
        preflight(owner, request.allowUnattendedRelogin)?.let { blocker -> return blocker.toFailure(payload) }
        return request.addToCartViaCompanion(payload)
    }

    /**
     * The owner's session, checked against *their* profile rather than a
     * companion-wide flag, with one unattended recovery attempt.
     *
     * Returns null when the profile is ready to be driven, or the blocker that
     * stops this hold. There is exactly one re-login: a second would only ask
     * rec.gov the same question inside the seconds-critical window.
     */
    private suspend fun preflight(
        owner: UserId,
        allowRelogin: Boolean,
    ): PreflightBlocker? {
        val client = session ?: return null
        return when (val health = client.health(owner)) {
            is CompanionSessionHealth.Active -> null
            is CompanionSessionHealth.Unavailable ->
                PreflightBlocker(RecGovSessionCodes.COMPANION_UNAVAILABLE, health.detail)
            // The companion answered but its own check threw. A login against a
            // service that is erroring is unlikely to fare better, and blaming
            // the owner's credentials for it would send them to re-login for
            // nothing — report the outage as the outage it is.
            is CompanionSessionHealth.CheckFailed ->
                PreflightBlocker(health.code ?: RecGovSessionCodes.AUTH_CHECK_EXCEPTION, COMPANION_ERROR_DETAIL)
            // Never signed in, or signed out: both are exactly what the one
            // unattended re-login exists for.
            // Cookies first, for both callers. A refresh is one API call with
            // no bot wall, and a lapsed JWT is the usual reason a session that
            // worked minutes ago reads as dead — so the interactive caller gets
            // it too, even though it will not pay for a credential login.
            is CompanionSessionHealth.NeverLoggedIn -> recover(client, owner, null, allowRelogin)
            is CompanionSessionHealth.Inactive -> recover(client, owner, health.code, allowRelogin)
        }
    }

    /**
     * Refresh, then — only for an unattended fire — a credential login.
     *
     * A person watching a spinner still gets the refresh: it is fast and often
     * enough. What they do not get is the credential login, which MFA would
     * block anyway and which would cost them a minute to learn nothing.
     */
    private suspend fun recover(
        client: RecGovProfileSessionPort,
        owner: UserId,
        healthCode: String?,
        allowRelogin: Boolean,
    ): PreflightBlocker? {
        if (client.refreshSession(owner) == CompanionActionResult.Ok) {
            log.info("recgov session refreshed from cookies for owner user_id={}", owner.value)
            return null
        }
        return if (allowRelogin) reLogin(client, owner, healthCode) else sessionExpired()
    }

    /**
     * The re-login's own answer, not a stand-in for it.
     *
     * Every refusal used to be rewritten to `recgov_session_expired` with
     * "re-login in Settings" — so a user with no credentials stored, a companion
     * at its browser cap, and a profile busy behind someone else's MFA challenge
     * were all told to do the one thing that could not help. The companion
     * already answers with the reason; passing it through is both less code and
     * the truth.
     */
    private suspend fun reLogin(
        client: RecGovProfileSessionPort,
        owner: UserId,
        healthCode: String?,
    ): PreflightBlocker? =
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
                PreflightBlocker(recovery.code, recovery.detail)
            }
        }

    private fun sessionExpired() = PreflightBlocker(RECGOV_SESSION_EXPIRED_ERROR, RECGOV_SESSION_EXPIRED_DETAIL)

    private fun profileIdFor(owner: UserId): String = session?.profileId(owner) ?: owner.value.toString()

    /**
     * The one place this adapter turns a companion code into a failure: the
     * category and the fallback sentence are decided together, so neither can be
     * forgotten at a call site.
     */
    private fun failed(
        code: String,
        detail: String?,
        payload: JsonObject,
        response: JsonObject?,
    ): AddToCartResult.Failed {
        val category = categoryOf(code)
        return AddToCartResult.Failed(
            providerId = id,
            error = code,
            detail = detail ?: undetailed(code, category),
            category = category,
            request = payload,
            response = response,
        )
    }

    private data class PreflightBlocker(
        val error: String,
        val detail: String?,
    )

    private fun PreflightBlocker.toFailure(payload: JsonObject): AddToCartResult.Failed = failed(error, detail, payload, response = null)

    private suspend fun AddToCartRequest.addToCartViaCompanion(payload: JsonObject): AddToCartResult =
        when (
            val outcome =
                runCatchingCancellable { companionAtc.addToCart(payload) }
                    .getOrElse { RecGovAtcOutcome.Failed(error = ERROR_COMPANION_EXCEPTION, detail = it.message) }
        ) {
            is RecGovAtcOutcome.Completed ->
                AddToCartResult.Completed(
                    providerId = id,
                    request = payload,
                    response = outcome.response,
                )
            is RecGovAtcOutcome.Failed -> failed(outcome.error, outcome.detail, payload, outcome.response)
        }

    private fun AddToCartRequest.toAtcPayload(profileId: String): JsonObject =
        buildJsonObject {
            put(FIELD_PROFILE_ID, profileId)
            put("start_date", arrivalDate.toString())
            put("end_date", checkoutDate.toString())
            put("campsite_id", target.vendorSiteId)
        }
}
