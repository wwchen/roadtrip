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
    fun `a database failure ends the sweep instead of the loop`() =
        runBlocking {
            val companion = FakeCompanion()
            val job =
                RecGovKeepaliveJob(
                    watchRepo = ThrowingWatchRepo(),
                    companion = companion,
                    profiles = FakeProfiles,
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
    ) = RecGovKeepaliveJob(
        watchRepo = repo,
        companion = companion,
        profiles = FakeProfiles,
        metrics = metrics,
        interval = sweepInterval,
    )

    private object FakeProfiles : RecGovProfileSessionPort {
        override fun profileId(userId: UserId): String = userId.value.toString()

        override suspend fun health(userId: UserId): CompanionSessionHealth = CompanionSessionHealth.Active

        override suspend fun reLogin(userId: UserId): CompanionActionResult = CompanionActionResult.Ok
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
        ): CompanionLoginResult = CompanionLoginResult.Ok

        override suspend fun completeMfa(
            profileId: String,
            challengeId: String,
            code: String,
        ): CompanionLoginResult = CompanionLoginResult.Ok

        override suspend fun logout(profileId: String): CompanionActionResult = CompanionActionResult.Ok

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
