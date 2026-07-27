package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.support.firstHandlerFor

/**
 * Selects the configured [IdentityProvider].
 *
 * One provider is active at a time, so [active] is the whole API today. The
 * registry exists anyway to match how alert and availability providers
 * dispatch, and so that supporting a second provider concurrently — the shape a
 * gradual vendor migration would take — is a change here rather than at every
 * call site.
 */
internal class IdentityProviderRegistry(
    private val providers: List<IdentityProvider>,
    private val activeId: IdentityProviderId,
) {
    init {
        require(providers.isNotEmpty()) { "IdentityProviderRegistry needs at least one provider" }
        requireNotNull(providers.firstHandlerFor(activeId)) {
            "no IdentityProvider handles '${activeId.slug}'; known: ${providers.map { it.id }}"
        }
    }

    fun active(): IdentityProvider = providers.firstHandlerFor(activeId)!!
}
