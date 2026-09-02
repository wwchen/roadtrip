package ca.floo.roadtrip.model.booking

import java.time.LocalDate

data class AddToCartRequest(
    /** Whose cart this lands in. Never a shared one. */
    val ownerUserId: Long,
    val target: BookingTarget,
    val arrivalDate: LocalDate,
    val checkoutDate: LocalDate,
    val campsiteLabel: String,
    val loop: String? = null,
    val siteType: String? = null,
    val campgroundId: Long? = null,
    val campgroundName: String? = null,
    val bookingUrl: String? = null,
    val stopWhenTriggered: Boolean,
    /**
     * Whether a dead session may be recovered by one unattended re-login.
     *
     * True for a watch firing at 3am: nobody is there, so spending up to a
     * minute on a re-login is the only chance of a hold. **False for a person
     * clicking Add to cart** — they are watching a spinner, an MFA prompt would
     * block the re-login anyway, and "session expired, fix it in Settings"
     * delivered in two seconds beats the same answer delivered in sixty.
     */
    val allowUnattendedRelogin: Boolean = true,
)
