package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class WatchScopeResolverTest : SharedDbTest() {
    private lateinit var campsiteRepo: CampsiteRepo
    private lateinit var watchRepo: AvailabilityWatchRepo
    private lateinit var resolver: WatchScopeResolver
    private var poiSeq = 0
    private var userSeq = 0

    @BeforeEach
    fun setUp() {
        campsiteRepo = CampsiteRepo(ctx)
        watchRepo = AvailabilityWatchRepo(ctx)
        resolver = WatchScopeResolver(campsiteRepo)
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedAppUser(email: String): UserId = UserRepo(ctx).create(
        email = "owner-${userSeq++}@example.com",
        displayName = null,
        isEmailVerified = true,
    ).id

    private fun insertPoi(): Long {
        val sourceId = "poi-scope-${poiSeq++}"
        return ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
            ).poiId
    }

    private fun insertCampsite(
        poiId: Long,
        vendorId: String,
    ): Long =
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "recgov",
            vendorId = vendorId,
            name = "Site $vendorId",
        )

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun createWatch(targets: List<AvailabilityWatchTargetRepo.TargetInput>): AvailabilityWatchRepo.Watch {
        val owner = seedAppUser(email = "owner@example.com")
        return watchRepo.create(
            AvailabilityWatchRepo.CreateInput(
                targets = targets,
                campsiteFilters = JsonObject(emptyMap()),
                startDate = LocalDate.parse("2026-07-04"),
                endDate = LocalDate.parse("2026-07-06"),
                cadenceSec = 60,
                triggerKinds = listOf("atc"),
                triggerConfig = JsonObject(emptyMap()),
                stopWhenTriggered = false,
                ownerUserId = owner.value,
            ),
        )
    }

    @Test
    fun `resolve unions reservables across a poi target and a reservable target`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val campsiteInA1 = insertCampsite(poiA, "a1")
        val campsiteInA2 = insertCampsite(poiA, "a2")
        val campsiteInB = insertCampsite(poiB, "b1")

        val watch =
            createWatch(
                listOf(
                    AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null),
                    AvailabilityWatchTargetRepo.TargetInput(poiId = null, campsiteId = campsiteInB),
                ),
            )

        val resolved = resolver.resolve(watch).map { it.id }.toSet()

        assertEquals(setOf(campsiteInA1, campsiteInA2, campsiteInB), resolved)
    }

    @Test
    fun `resolve de-duplicates a reservable reachable via two targets`() {
        val poi = insertPoi()
        val reservable = insertCampsite(poi, "dup")

        val watch =
            createWatch(
                listOf(
                    AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null),
                    AvailabilityWatchTargetRepo.TargetInput(poiId = null, campsiteId = reservable),
                ),
            )

        val resolved = resolver.resolve(watch)

        assertEquals(1, resolved.size)
        assertEquals(reservable, resolved.single().id)
    }
}
