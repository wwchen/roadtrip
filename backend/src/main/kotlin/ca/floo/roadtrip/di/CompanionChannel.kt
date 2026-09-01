package ca.floo.roadtrip.di

import ca.floo.roadtrip.service.settings.CompanionSessionPort

/**
 * The single companion channel, held so Koin can carry an *optional* one.
 *
 * There is one companion service, so there should be one client to it. Three
 * grew instead — the credential service, the keepalive job and the ATC executor
 * each built their own — and every `java.net.http.HttpClient` brings a selector
 * thread and a connection pool with it. The `takeIf { companionEnabled }?.let`
 * construction was copy-pasted alongside them, which is the usual way two
 * copies of a thing drift.
 *
 * A wrapper rather than a `single<CompanionSessionPort?>`: a Koin single that
 * produces null throws at resolution, and a deployment with no companion
 * configured must still boot (see `ServiceModuleWiringTest`). [session] is null
 * exactly then.
 */
internal class CompanionChannel(
    val session: CompanionSessionPort?,
)
