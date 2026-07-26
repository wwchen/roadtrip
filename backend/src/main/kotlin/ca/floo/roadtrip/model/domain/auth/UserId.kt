package ca.floo.roadtrip.model.domain.auth

/**
 * Identifier for an `app_user` row.
 *
 * Deliberately a value class rather than a bare `Long`, unlike the other ids in
 * this codebase. Ownership checks are the one place where passing the wrong id
 * is a security bug rather than a correctness bug: a `poiId` reaching a
 * `WHERE user_id = ?` predicate would silently scope a query to the wrong
 * person. The wrapper makes that a compile error. Introduced here, before the
 * authz pass creates call sites, because retrofitting it later is a wide change.
 */
@JvmInline
value class UserId(
    val value: Long,
)
