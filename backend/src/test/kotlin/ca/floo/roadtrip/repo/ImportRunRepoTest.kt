package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ImportRunRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM import_runs")
    }

    @Test
    fun `start then complete records seen count and status`() {
        val repo = ImportRunRepo(ctx)
        val id = repo.start("recgov")
        repo.complete(id, seenCount = 42)
        val row = ctx.fetchOne("SELECT status, seen_count FROM import_runs WHERE id = ?", id)!!
        assertEquals("completed", row.get("status", String::class.java))
        assertEquals(42, row.get("seen_count", Int::class.java))
    }

    @Test
    fun `fail records failed status and notes`() {
        val repo = ImportRunRepo(ctx)
        val id = repo.start("recgov")
        repo.fail(id, "tripwire tripped")
        val row = ctx.fetchOne("SELECT status, notes FROM import_runs WHERE id = ?", id)!!
        assertEquals("failed", row.get("status", String::class.java))
        assertEquals("tripwire tripped", row.get("notes", String::class.java))
    }
}
