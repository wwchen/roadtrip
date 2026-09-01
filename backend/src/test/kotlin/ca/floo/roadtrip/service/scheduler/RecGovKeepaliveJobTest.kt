package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.observability.KeepaliveOutcome
import ca.floo.roadtrip.observability.PollSkipReason
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovProfileSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val sweepInterval: Duration = Duration.ofMinutes(15)

class RecGovKeepaliveJobTest {
    @Test
    fun `a sweep arms the distinct owners of active atc watches and refreshes each`() =
        runBlocking {
            val companion = FakeCompanion()
            val job = job(owners = listOf(4L, 9L), companion = companion)

            job.sweepOnce()

            assertEquals(listOf(listOf("4", "9")), companion.keepWarmPushes)
            assertEquals(listOf("4", "9"), companion.refreshed)
        }

    @Test
    fun `the armed set is read from active atc watches only`() =
        runBlocking {
            val repo = FakeWatchRepo(owners = listOf(4L))
            job(repo = repo, companion = FakeCompanion()).sweepOnce()

            assertEquals(listOf(WatchStatus.ACTIVE to AvailabilityTriggerKinds.ATC), repo.queries)
        }

    @Test
    fun `an empty armed set is still pushed, so a disarmed profile is released`() =
        runBlocking {
            val companion = FakeCompanion()
            job(owners = emptyList(), companion = companion).sweepOnce()

            assertEquals(listOf(emptyList<String>()), companion.keepWarmPushes)
            assertTrue(companion.refreshed.isEmpty())
        }

    @Test
    fun `each refreshed profile is counted`() =
        runBlocking {
            val metrics = RecordingMetrics()
            job(owners = listOf(4L, 9L), companion = FakeCompanion(), metrics = metrics).sweepOnce()

            assertEquals(listOf(KeepaliveOutcome.REFRESHED, KeepaliveOutcome.REFRESHED), metrics.keepalives)
        }

    @Test
    fun `a declined refresh is counted without stopping the rest of the sweep`() =
        runBlocking {
            val metrics = RecordingMetrics()
            val companion = FakeCompanion(refuseRefreshFor = setOf("4"))
            job(owners = listOf(4L, 9L), companion = companion, metrics = metrics).sweepOnce()

            assertEquals(listOf(KeepaliveOutcome.FAILED, KeepaliveOutcome.REFRESHED), metrics.keepalives)
            assertEquals(listOf("4", "9"), companion.refreshed)
        }

    @Test
    fun `an unreachable companion is counted as unavailable and refreshes nothing`() =
        runBlocking {
            val metrics = RecordingMetrics()
            val companion = FakeCompanion(keepWarmFailure = RecGovSessionCodes.COMPANION_UNAVAILABLE)
            job(owners = listOf(4L, 9L), companion = companion, metrics = metrics).sweepOnce()

            assertEquals(listOf(KeepaliveOutcome.UNAVAILABLE, KeepaliveOutcome.UNAVAILABLE), metrics.keepalives)
            assertTrue(companion.refreshed.isEmpty())
        }

    @Test
    fun `a credentialed user with a live session is kept warm even with no watch`() =
        runBlocking {
            // The live bug: a headed login lapsed ~30 minutes later because this
            // user had no active atc watch, so nothing refreshed them.
            val companion = FakeCompanion()
            job(owners = emptyList(), companion = companion, credentialed = listOf(9L)).sweepOnce()

            assertEquals(listOf(listOf("9")), companion.keepWarmPushes)
            assertEquals(listOf("9"), companion.refreshed)
        }

    @Test
    fun `a credentialed user the companion never signed in is skipped`() =
        runBlocking {
            // Nothing to keep alive, and launching a browser to rediscover that
            // every cadence is pure cost.
            val companion = FakeCompanion()
            job(companion = companion, credentialed = listOf(9L), neverLoggedIn = setOf(9L)).sweepOnce()

            assertEquals(listOf(emptyList<String>()), companion.keepWarmPushes)
        }

    @Test
    fun `armed profiles come first and survive the cap`() =
        runBlocking {
            // Armed watches may fire in seconds; everyone else merely has a
            // session worth not losing. The cap must not cost an armed profile.
            val companion = FakeCompanion()
            job(
                owners = listOf(4L),
                companion = companion,
                credentialed = listOf(7L, 8L, 9L),
                maxProfiles = 2,
            ).sweepOnce()

            assertEquals(listOf(listOf("4", "7")), companion.keepWarmPushes)
        }

    @Test
    fun `an armed owner who is also credentialed is kept warm once`() =
        runBlocking {
            val companion = FakeCompanion()
            job(owners = listOf(4L), companion = companion, credentialed = listOf(4L)).sweepOnce()

            assertEquals(listOf(listOf("4")), companion.keepWarmPushes)
        }

    @Test
    fun `a database failure ends the sweep instead of the loop`() =
        runBlocking {
            val companion = FakeCompanion()
            val job =
                RecGovKeepaliveJob(
                    watchRepo = ThrowingWatchRepo(),
                    companion = companion,
                    profiles = FakeProfiles(),
                    credentials = { emptyList() },
                    metrics = RecordingMetrics(),
                    interval = sweepInterval,
                )

            job.sweepOnce()

            assertTrue(companion.keepWarmPushes.isEmpty())
        }

    private fun job(
        owners: List<Long> = emptyList(),
        repo: AvailabilityWatchRepo = FakeWatchRepo(owners),
        companion: CompanionSessionPort,
        metrics: RoadtripMetrics = RecordingMetrics(),
        credentialed: List<Long> = emptyList(),
        neverLoggedIn: Set<Long> = emptySet(),
        maxProfiles: Int = DEFAULT_MAX_KEEP_WARM_PROFILES,
    ) = RecGovKeepaliveJob(
        watchRepo = repo,
        companion = companion,
        profiles = FakeProfiles(neverLoggedIn),
        credentials = { credentialed },
        metrics = metrics,
        interval = sweepInterval,
        maxProfiles = maxProfiles,
    )

    private class FakeProfiles(
        /** Users the companion has never signed in — nothing to keep alive. */
        private val neverLoggedIn: Set<Long> = emptySet(),
    ) : RecGovProfileSessionPort {
        override fun profileId(userId: UserId): String = userId.value.toString()

        override suspend fun health(userId: UserId): CompanionSessionHealth =
            if (userId.value in neverLoggedIn) CompanionSessionHealth.NeverLoggedIn else CompanionSessionHealth.Active

        override suspend fun reLogin(userId: UserId): CompanionActionResult = CompanionActionResult.Ok

        override suspend fun refreshSession(userId: UserId): CompanionActionResult = CompanionActionResult.Ok
    }

    private open class FakeWatchRepo(
        private val owners: List<Long>,
    ) : AvailabilityWatchRepo(ctx = DSL.using(SQLDialect.POSTGRES)) {
        val queries = mutableListOf<Pair<WatchStatus, String>>()

        override fun distinctOwnersByTriggerKind(
            status: WatchStatus,
            triggerKind: String,
        ): List<Long> {
            queries += status to triggerKind
            return owners
        }
    }

    private class ThrowingWatchRepo : FakeWatchRepo(emptyList()) {
        override fun distinctOwnersByTriggerKind(
            status: WatchStatus,
            triggerKind: String,
        ): List<Long> = throw IllegalStateException("connection refused")
    }

    private class FakeCompanion(
        private val keepWarmFailure: String? = null,
        private val refuseRefreshFor: Set<String> = emptySet(),
    ) : CompanionSessionPort {
        val keepWarmPushes = mutableListOf<List<String>>()
        val refreshed = mutableListOf<String>()

        override suspend fun markKeepWarm(profileIds: Collection<String>): CompanionActionResult {
            keepWarmPushes += profileIds.toList()
            return keepWarmFailure?.let { CompanionActionResult.Failed(it) } ?: CompanionActionResult.Ok
        }

        override suspend fun refresh(profileId: String): CompanionActionResult {
            refreshed += profileId
            return if (profileId in refuseRefreshFor) {
                CompanionActionResult.Failed(RecGovSessionCodes.NOT_AUTHENTICATED)
            } else {
                CompanionActionResult.Ok
            }
        }

        override suspend fun login(
            profileId: String,
            username: String,
            password: String,
            unattended: Boolean,
        ): CompanionLoginResult = CompanionLoginResult.Ok

        override suspend fun completeMfa(
            profileId: String,
            challengeId: String,
            code: String,
        ): CompanionLoginResult = CompanionLoginResult.Ok

        override suspend fun logout(profileId: String): CompanionActionResult = CompanionActionResult.Ok

        override suspend fun destroyProfile(profileId: String): CompanionActionResult = CompanionActionResult.Ok

        override suspend fun verify(profileId: String): CompanionActionResult = CompanionActionResult.Ok

        override suspend fun health(profileId: String): CompanionSessionHealth = CompanionSessionHealth.Active
    }

    private class RecordingMetrics : RoadtripMetrics {
        val keepalives = mutableListOf<KeepaliveOutcome>()

        override fun recgovKeepaliveProfile(outcome: KeepaliveOutcome) {
            keepalives += outcome
        }

        override fun availabilityFetchCompleted(
            provider: BookingProvider,
            outcome: String,
            durationMs: Int?,
        ) = Unit

        override fun availabilityRunFinished(
            status: String,
            durationMs: Int,
        ) = Unit

        override fun availabilityPollSkipped(
            provider: BookingProvider,
            reason: PollSkipReason,
        ) = Unit

        override fun watchTriggerFired(
            kinds: Set<String>,
            delivered: Boolean,
        ) = Unit

        override fun ingestRunFinished(
            target: String,
            kind: String,
            status: String,
        ) = Unit
    }
}
