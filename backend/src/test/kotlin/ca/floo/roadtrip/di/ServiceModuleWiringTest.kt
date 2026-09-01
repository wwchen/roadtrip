package ca.floo.roadtrip.di

import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.settings.RecGovCredentialService
import ca.floo.roadtrip.service.settings.UserSettingsService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Boot-time DI regression guard.
 *
 * `UserSettingsService`'s optional deps (the AES-GCM cipher and the per-user
 * Slack client) are null when no encryption key / Slack is configured — the
 * default for a fresh install, and `RecGovCredentialService` has the same shape
 * with its cipher and its companion client. They must be built INLINE in the service's
 * definition, never registered as `single<T?>`: a Koin `single { }` that
 * produces null throws at resolution ("Single instance created couldn't return
 * value"), which crashed application boot. This test resolves the service from
 * the real [serviceModule] with that exact null-yielding config.
 */
class ServiceModuleWiringTest : SharedDbTest() {
    @Test
    fun `the settings services resolve when no encryption key, Slack or companion is configured`() {
        // auth/secrets/slack keys omitted => those sections resolve to null: the exact
        // config that crashed boot. Only the two globally-required durations are supplied.
        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "5m",
                    "roadtrip.availability.provider-cooldown" to "5m",
                ),
            )

        // createEagerInstances = false: resolve only UserSettingsService's own graph,
        // not the module's eager schedulers (which need a CoroutineScope etc.).
        val app =
            koinApplication(createEagerInstances = false) {
                modules(
                    repoModule,
                    serviceModule,
                    module {
                        single { config }
                        // The repos only capture the DSLContext at construction; a real
                        // one from the shared test container keeps the graph honest.
                        single<DSLContext> { ctx }
                    },
                )
            }

        try {
            assertNotNull(
                app.koin.get<UserSettingsService>(),
                "UserSettingsService must resolve with a null cipher and null Slack client",
            )
            assertNotNull(
                app.koin.get<RecGovCredentialService>(),
                "RecGovCredentialService must resolve with a null cipher and no companion",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun `every companion caller shares one client`() {
        // Three callers each built their own HttpClient — three selector threads
        // and three pools to one service — with the enabled-check copy-pasted
        // beside each. One single, resolved by all of them.
        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "5m",
                    "roadtrip.availability.provider-cooldown" to "5m",
                    "roadtrip.booking.recgov-atc.companion-base-url" to "http://companion.invalid:8770",
                ),
            )

        val app =
            koinApplication(createEagerInstances = false) {
                modules(
                    repoModule,
                    serviceModule,
                    module {
                        single { config }
                        single<DSLContext> { ctx }
                    },
                )
            }

        try {
            val channel = app.koin.get<CompanionChannel>()
            assertNotNull(channel.session, "a configured companion must yield a session client")
            assertSame(channel, app.koin.get<CompanionChannel>(), "the channel must be a single, not per-resolution")
        } finally {
            app.close()
        }
    }
}
