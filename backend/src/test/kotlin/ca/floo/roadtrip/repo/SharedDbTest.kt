package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/**
 * Base for DB-backed tests. Each subclass gets its OWN database cloned from the
 * shared migrated template (see [SharedTestDb]) — so test classes are fully
 * isolated and can run in parallel without truncating each other's data.
 *
 * The database is created lazily on first use of [ds]/[ctx] and dropped
 * implicitly when the container is reaped. Methods within a class run on one
 * thread against this one database, so per-method `@BeforeEach` cleanup still
 * works exactly as before.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SharedDbTest {
    protected val ds: HikariDataSource by lazy { SharedTestDb.createDatabase() }
    protected val ctx: DSLContext by lazy { DSL.using(ds, SQLDialect.POSTGRES) }

    @AfterAll
    fun closeSharedDb() {
        ds.close()
    }
}
