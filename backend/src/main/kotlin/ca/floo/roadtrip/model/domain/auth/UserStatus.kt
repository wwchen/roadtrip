package ca.floo.roadtrip.model.domain.auth

/**
 * Account state. Persisted as [wireValue] in `app_user.status`, which carries a
 * matching CHECK constraint — adding an entry here requires a migration.
 *
 * [DISABLED] is a hard stop: session resolution must refuse to produce a
 * [Principal.User] for a disabled account, so revoking access does not depend on
 * also revoking every outstanding session.
 */
enum class UserStatus(
    val wireValue: String,
) {
    ACTIVE("active"),
    DISABLED("disabled"),
    ;

    companion object {
        fun parse(value: String?): UserStatus? = entries.firstOrNull { it.wireValue == value }
    }
}
