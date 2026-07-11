package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import ca.floo.roadtrip.models.metadata.registry.CampsiteParentJoinerEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EtlOrchestratorJoinerTest : SharedDbTest() {
    @BeforeEach
    fun reset() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `joiner chunks are retry-safe after a later chunk fails`() {
        val oldCampgroundId =
            ctx.seedCampground(name = "Old Parent", source = TEST_VENDOR, sourceId = "old-parent", refresh = false)
        val targetCampgroundId =
            ctx.seedCampground(name = "Target Parent", source = TEST_VENDOR, sourceId = "target-parent", refresh = false)
        val campsiteIds =
            listOf(
                ctx.seedCampsite(oldCampgroundId, vendor = TEST_VENDOR, vendorId = "site-1", refresh = false),
                ctx.seedCampsite(oldCampgroundId, vendor = TEST_VENDOR, vendorId = "site-2", refresh = false),
                ctx.seedCampsite(oldCampgroundId, vendor = TEST_VENDOR, vendorId = "site-3", refresh = false),
            )
        val joiner =
            FlakyJoiner(
                campsiteIds = campsiteIds,
                targetCampgroundId = targetCampgroundId,
            )
        val orchestrator =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = File(System.getProperty("java.io.tmpdir")),
                poiRegistry = registry(),
                joinerRegistry = mapOf(joiner.adapter to joiner),
                joinerChunkSize = TEST_JOINER_CHUNK_SIZE,
            )

        assertFailsWith<DataAccessException> {
            orchestrator.runJoiner(TEST_JOINER_NAME)
        }

        assertEquals(targetCampgroundId, campgroundOf(campsiteIds[0]))
        assertEquals(oldCampgroundId, campgroundOf(campsiteIds[1]))
        assertEquals(oldCampgroundId, campgroundOf(campsiteIds[2]))

        val retry = orchestrator.runJoiner(TEST_JOINER_NAME)

        assertEquals(campsiteIds.size, retry.linksDiscovered)
        assertEquals(2, retry.linksInserted)
        assertEquals(targetCampgroundId, campgroundOf(campsiteIds[0]))
        assertEquals(targetCampgroundId, campgroundOf(campsiteIds[1]))
        assertEquals(targetCampgroundId, campgroundOf(campsiteIds[2]))
    }

    private fun campgroundOf(campsiteId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM campsites WHERE id = ?", campsiteId)!!
            .get("campground_id", Long::class.java)

    private fun registry(): PoiRegistry =
        PoiRegistry(
            dataSources = emptyList(),
            poiData = emptyList(),
            campsiteParentJoiners =
                listOf(
                    CampsiteParentJoinerEntry(
                        name = TEST_JOINER_NAME,
                        adapter = FlakyJoiner.ADAPTER,
                    ),
                ),
        )

    private class FlakyJoiner(
        private val campsiteIds: List<Long>,
        private val targetCampgroundId: Long,
    ) : CampsiteParentJoiner {
        override val adapter: String = ADAPTER
        private var attempts = 0

        override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> {
            attempts += 1
            val secondTarget =
                if (attempts == 1) {
                    INVALID_CAMPGROUND_ID
                } else {
                    targetCampgroundId
                }
            return listOf(
                CampsiteParentLink(campsiteId = campsiteIds[0], campgroundId = targetCampgroundId),
                CampsiteParentLink(campsiteId = campsiteIds[1], campgroundId = secondTarget),
                CampsiteParentLink(campsiteId = campsiteIds[2], campgroundId = targetCampgroundId),
            )
        }

        companion object {
            const val ADAPTER = "FlakyJoiner"
        }
    }

    companion object {
        private const val TEST_VENDOR = "joiner-test"
        private const val TEST_JOINER_NAME = "Test Joiner"
        private const val TEST_JOINER_CHUNK_SIZE = 1
        private const val INVALID_CAMPGROUND_ID = -1L
    }
}
