package ca.floo.roadtrip.service.notification.email

/**
 * Builds the "manage this watch" link for alert emails: the same in-app deep
 * link the logged-in UI uses, with the magic-link management token appended
 * when one was minted. The frontend reads `token` to let the watches page
 * act on this one watch without a session — see docs/backend-architecture.md
 * "Alert seam" for the token's scope and expiry.
 */
internal fun String.modifyUrl(
    watchId: Long,
    managementToken: String?,
): String {
    val base = "${trimEnd('/')}/watches?action=modify&id=$watchId"
    return managementToken?.let { "$base&token=$it" } ?: base
}
