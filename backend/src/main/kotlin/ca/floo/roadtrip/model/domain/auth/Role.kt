package ca.floo.roadtrip.model.domain.auth

/**
 * Coarse authorization role. Persisted as [wireValue] in `user_role.role`,
 * which carries a matching CHECK constraint — adding an entry here requires a
 * migration to widen it.
 *
 * Nothing consumes roles until the authz pass; they are modelled now so the
 * schema and the domain type land together.
 */
enum class Role(
    val wireValue: String,
) {
    ADMIN("admin"),
    ;

    companion object {
        fun parse(value: String?): Role? = entries.firstOrNull { it.wireValue == value }
    }
}
