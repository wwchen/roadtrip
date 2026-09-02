package ca.floo.roadtrip.di

import ca.floo.roadtrip.client.companion.CompanionSessionClient
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.settings.CompanionSessionPort

/**
 * The single companion channel, held so Koin can carry an *optional* one.
 *
 * There is one companion service, so there is one client to it. Three grew
 * instead — the credential service, the keepalive job and the ATC executor each
 * built their own — and every `java.net.http.HttpClient` brings a selector
 * thread and a connection pool with it. The `takeIf { companionEnabled }?.let`
 * construction was copy-pasted alongside them, which is the usual way two
 * copies of a thing drift.
 *
 * [session] and [atc] are one object seen through the two ports its callers
 * depend on, so neither half can be configured without the other.
 *
 * A wrapper rather than a `single<CompanionSessionPort?>`: a Koin single that
 * produces null throws at resolution, and a deployment with no companion
 * configured must still boot (see `ServiceModuleWiringTest`). Both are null
 * exactly then.
 */
internal class CompanionChannel(
    private val companionClient: CompanionSessionClient?,
) {
    val session: CompanionSessionPort? get() = companionClient

    val atc: RecGovAtcExecutor? get() = companionClient
}
