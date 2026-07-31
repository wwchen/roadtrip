package ca.floo.roadtrip.config

private const val BOOTSTRAP_EMAILS_KEY = "bootstrap-emails"

/**
 * Authorization bootstrap: the email addresses that are granted
 * [ca.floo.roadtrip.model.domain.auth.Role.ADMIN] when they sign in.
 *
 * This exists because the first admin cannot be granted by an admin. Roles are
 * otherwise only readable — `user_role` has no writer in the request path — so
 * without this the `admin` role is unreachable and every `HasRole(ADMIN)` route
 * is closed to everybody.
 *
 * A **list** rather than a single address, because a second admin (or an ops
 * rotation) should not require a code change, and the parsing cost is identical.
 *
 * Unlike [AuthConfig] and [SlackConfig] this is not nullable. Those have a
 * genuine disabled state that callers branch on; an empty bootstrap list is not
 * a disabled subsystem, it is a list that matches nobody — and the grant path
 * already handles that by doing nothing. One less null to thread.
 *
 * Addresses are lowercased and trimmed here so the comparison site does not have
 * to remember to; see
 * [ca.floo.roadtrip.service.auth.UserProvisioningService].
 */
data class AdminConfig(
    val bootstrapEmails: Set<String>,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AdminConfig =
            AdminConfig(
                bootstrapEmails =
                    config
                        .csvSet(BOOTSTRAP_EMAILS_KEY)
                        .mapTo(mutableSetOf()) { it.lowercase() },
            )
    }
}
