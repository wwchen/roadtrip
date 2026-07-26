package ca.floo.roadtrip.service.auth

/**
 * Dispatch key for [ClaimsDialect]. Wraps the configured provider slug so
 * lookup is a typed call rather than a bare string comparison.
 */
@JvmInline
value class ClaimsDialectId(
    val slug: String,
)
