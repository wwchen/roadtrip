package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilityWatchRepoTest : SharedDbTest() {
    private var poiSeq = 0
    private var userSeq = 0

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedAppUser(): UserId =
        UserRepo(ctx).create(email = "owner-${userSeq++}@example.com", displayName = null, isEmailVerified = true).id

    private fun insertPoller(poiId: Long): Long =
        ctx
            .fetchOne(
                "INSERT INTO availability_poller (provider, parent_ref, poi_id) VALUES ('recgov', ?, ?) RETURNING id",
                "parent-$poiId",
                poiId,
            )!!
            .get("id", Long::class.java)

    private fun linkWatchPoller(
        watchId: Long,
        pollerId: Long,
    ) = ctx.execute("INSERT INTO availability_watch_poller (watch_id, poller_id) VALUES (?, ?)", watchId, pollerId)

    private fun insertRun(
        pollerId: Long,
        status: String,
        error: String?,
        startedAt: String,
        completedAt: String?,
    ) = ctx.execute(
        """
        INSERT INTO availability_run (poller_id, status, error, started_at, completed_at)
        VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)
        """.trimIndent(),
        pollerId,
        status,
        error,
        startedAt,
        completedAt,
    )

    private fun insertPoi(): Long {
        val sourceId = "poi-repo-${poiSeq++}"
        return ctx.seedCatalogPoi(sourceId = sourceId, name = "Upper Pines", lon = -119.56, lat = 37.74).poiId
    }

    private fun createInput(
        targets: List<AvailabilityWatchTargetRepo.TargetInput>,
        ownerUserId: Long,
    ): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            targets = targets,
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = listOf("atc"),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = false,
            ownerUserId = ownerUserId,
        )

    @Test
    fun `create persists a multi-poi target set`() {
        val owner = seedAppUser()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)

        val watch =
            repo.create(
                createInput(
                    listOf(
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null),
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null),
                    ),
                    ownerUserId = owner.value,
                ),
            )

        assertEquals(2, watch.targets.size)
        assertEquals(setOf(poiA, poiB), watch.targets.mapNotNull { it.poiId }.toSet())
    }

    @Test
    fun `findById reloads the persisted target set`() {
        val owner = seedAppUser()
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created =
            repo.create(
                createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null)), ownerUserId = owner.value),
            )

        val reloaded = repo.findById(created.id)!!

        assertEquals(1, reloaded.targets.size)
        assertEquals(poi, reloaded.targets.single().poiId)
    }

    @Test
    fun `update replaces the target set when targets is provided`() {
        val owner = seedAppUser()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created =
            repo.create(
                createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null)), ownerUserId = owner.value),
            )

        val updated =
            repo.update(
                created.id,
                AvailabilityWatchRepo.UpdateInput(
                    targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null)),
                ),
            )!!

        assertEquals(1, updated.targets.size)
        assertEquals(poiB, updated.targets.single().poiId)
    }

    @Test
    fun `update without targets leaves the existing target set untouched`() {
        val owner = seedAppUser()
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created =
            repo.create(
                createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null)), ownerUserId = owner.value),
            )

        val updated = repo.update(created.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))!!

        assertEquals(1, updated.targets.size)
        assertEquals(poi, updated.targets.single().poiId)
        assertEquals(WatchStatus.PAUSED, updated.status)
    }

    @Test
    fun `list filtered by poiId matches watches whose target set includes that poi`() {
        val owner = seedAppUser()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val watchWithA =
            repo.create(
                createInput(
                    listOf(
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null),
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null),
                    ),
                    ownerUserId = owner.value,
                ),
            )
        val watchWithBOnly =
            repo.create(
                createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null)), ownerUserId = owner.value),
            )

        val filtered = repo.list(poiId = poiA)

        assertEquals(listOf(watchWithA.id), filtered.map { it.id })
        assertTrue(repo.list(poiId = poiB).map { it.id }.toSet() == setOf(watchWithA.id, watchWithBOnly.id))
    }

    @Test
    fun `list surfaces the latest run status and error across the watch's pollers`() {
        val owner = seedAppUser()
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val watch =
            repo.create(
                createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null)), ownerUserId = owner.value),
            )
        val poller = insertPoller(poi)
        linkWatchPoller(watch.id, poller)
        // Older successful run, then a newer failed run — the newer one wins.
        insertRun(poller, "completed", null, "2026-07-01T00:00:00Z", "2026-07-01T00:00:05Z")
        insertRun(poller, "failed", "rate_limited", "2026-07-02T00:00:00Z", "2026-07-02T00:00:03Z")

        val listed = repo.list(poiId = poi).single()

        assertEquals("failed", listed.lastRun?.status)
        assertEquals("rate_limited", listed.lastRun?.error)
        assertNotNull(listed.lastRun?.completedAt)
        // findById resolves the same latest-run snapshot.
        assertEquals("failed", repo.findById(watch.id)!!.lastRun?.status)
    }

    @Test
    fun `list leaves lastRun null when the watch has never polled`() {
        val owner = seedAppUser()
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null)), ownerUserId = owner.value))

        assertNull(repo.list(poiId = poi).single().lastRun)
    }

    @Test
    fun `list scopes to owner and null owner returns all`() {
        val owner = seedAppUser()
        val other = seedAppUser()
        val poiId = insertPoi()

        val repo = AvailabilityWatchRepo(ctx)
        val mine =
            repo.create(
                createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)), ownerUserId = owner.value),
            )
        repo.create(
            createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)), ownerUserId = other.value),
        )

        val ownScoped = repo.list(ownerUserId = owner.value)
        assertEquals(listOf(mine.id), ownScoped.map { it.id })
        assertEquals(owner.value, ownScoped.single().ownerUserId)

        val all = repo.list(ownerUserId = null)
        assertEquals(2, all.size)
        assertEquals(2, repo.count(ownerUserId = null))
        assertEquals(1, repo.count(ownerUserId = owner.value))
    }

    @Test
    fun `countByTriggerKind counts only the owner's active watches carrying that kind`() {
        val owner = seedAppUser()
        val other = seedAppUser()
        val poiId = insertPoi()
        val target = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null))
        val repo = AvailabilityWatchRepo(ctx)

        // Carries atc alongside a notify kind — containment, not equality.
        repo.create(createInput(target, ownerUserId = owner.value).copy(triggerKinds = listOf("email_notify", "atc")))
        // Same owner, no atc.
        repo.create(createInput(target, ownerUserId = owner.value).copy(triggerKinds = listOf("email_notify")))
        // Same owner, atc but paused.
        val paused = repo.create(createInput(target, ownerUserId = owner.value))
        repo.update(paused.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))
        // Another owner's active atc watch.
        repo.create(createInput(target, ownerUserId = other.value))

        assertEquals(1, repo.countByTriggerKind(owner.value, WatchStatus.ACTIVE, "atc"))
        assertEquals(1, repo.countByTriggerKind(other.value, WatchStatus.ACTIVE, "atc"))
        assertEquals(2, repo.countByTriggerKind(owner.value, WatchStatus.ACTIVE, "email_notify"))
    }
}
