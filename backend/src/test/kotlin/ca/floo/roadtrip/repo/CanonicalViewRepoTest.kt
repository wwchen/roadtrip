package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CanonicalViewRepoTest : SharedDbTest() {
    @BeforeEach
    fun resetCatalog() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `refresh keeps each active campground and campsite as its own canonical row`() {
        val recgovCg = seedCampgroundRow(name = "Upper Pines", dataSource = "recgov")
        val campflareCg = seedCampgroundRow(name = "Upper Pines", dataSource = "campflare")
        val recgovSite = ctx.seedCampsite(campgroundId = recgovCg, vendor = "recgov", vendorId = "recgov-a")
        val campflareSite = ctx.seedCampsite(campgroundId = campflareCg, vendor = "campflare", vendorId = "cf-a")

        CanonicalViewRepo(ctx).refreshCanonicalViews()

        assertEquals(
            listOf(
                CanonicalRow(id = recgovCg, groupKey = recgovCg, memberIds = listOf(recgovCg), sources = listOf("recgov")),
                CanonicalRow(
                    id = campflareCg,
                    groupKey = campflareCg,
                    memberIds = listOf(campflareCg),
                    sources = listOf("campflare"),
                ),
            ),
            campgroundCanonicalRows(),
        )
        assertEquals(
            listOf(
                CanonicalRow(
                    id = recgovSite,
                    groupKey = recgovSite,
                    memberIds = listOf(recgovSite),
                    sources = listOf("recgov"),
                ),
                CanonicalRow(
                    id = campflareSite,
                    groupKey = campflareSite,
                    memberIds = listOf(campflareSite),
                    sources = listOf("campflare"),
                ),
            ),
            campsiteCanonicalRows(),
        )
    }

    @Test
    fun `refresh filters deleted catalog rows without repointing dependents`() {
        val activeCg = seedCampgroundRow(name = "Active", dataSource = "recgov")
        val deletedCg = seedCampgroundRow(name = "Deleted", dataSource = "campflare")
        val deletedSite = ctx.seedCampsite(campgroundId = deletedCg, vendor = "campflare", vendorId = "cf-deleted")
        ctx.execute("UPDATE campgrounds SET deleted_at = now() WHERE id = ?", deletedCg)
        ctx.execute("UPDATE campsites SET deleted_at = now() WHERE id = ?", deletedSite)

        CanonicalViewRepo(ctx).refreshCanonicalViews()

        assertEquals(listOf(activeCg), campgroundCanonicalRows().map { it.id })
        assertEquals(emptyList<Long>(), campsiteCanonicalRows().map { it.id })
    }

    private fun seedCampgroundRow(
        name: String,
        dataSource: String,
    ): Long {
        val primaryRefId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO vendor_refs (vendor, entity_type, external_id)
                    VALUES (?, 'campground', ?)
                    RETURNING id
                    """.trimIndent(),
                    dataSource,
                    "seed-primary:$dataSource:$name",
                )!!
                .get("id", Long::class.java)
        val campgroundId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO campgrounds (name, kind, data_source, primary_vendor_ref_id)
                    VALUES (?, 'campground', ?, ?)
                    RETURNING id
                    """.trimIndent(),
                    name,
                    dataSource,
                    primaryRefId,
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id) VALUES (?, ?)",
            campgroundId,
            primaryRefId,
        )
        return campgroundId
    }

    private fun campgroundCanonicalRows(): List<CanonicalRow> =
        ctx
            .fetch(
                """
                SELECT id, group_key, member_ids, member_sources
                FROM campground_canonical
                ORDER BY id
                """.trimIndent(),
            ).map(::canonicalRow)

    private fun campsiteCanonicalRows(): List<CanonicalRow> =
        ctx
            .fetch(
                """
                SELECT id, group_key, member_ids, member_sources
                FROM campsite_canonical
                ORDER BY id
                """.trimIndent(),
            ).map(::canonicalRow)

    private fun canonicalRow(record: org.jooq.Record): CanonicalRow =
        CanonicalRow(
            id = record.get("id", Long::class.java),
            groupKey = record.get("group_key", Long::class.java),
            memberIds = longArray(record.get("member_ids")),
            sources = stringArray(record.get("member_sources")),
        )

    private data class CanonicalRow(
        val id: Long,
        val groupKey: Long,
        val memberIds: List<Long>,
        val sources: List<String>,
    )

    private fun longArray(value: Any?): List<Long> =
        when (value) {
            is Array<*> -> value.map { (it as Number).toLong() }
            is java.sql.Array -> longArray(value.array)
            else -> emptyList()
        }

    private fun stringArray(value: Any?): List<String> =
        when (value) {
            is Array<*> -> value.map { it.toString() }
            is java.sql.Array -> stringArray(value.array)
            else -> emptyList()
        }
}
