package ca.floo.roadtrip.service.auth

/**
 * Dispatch key for [IdentityProvider]. One provider is configured at a time
 * today; the key exists so selection is a registry lookup rather than a
 * hardcoded reference, matching how availability and alert providers dispatch.
 */
@JvmInline
value class IdentityProviderId(
    val slug: String,
)
