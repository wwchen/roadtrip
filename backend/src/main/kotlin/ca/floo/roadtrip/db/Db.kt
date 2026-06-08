package ca.floo.roadtrip.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    companion object {
        fun fromEnv(): DbConfig =
            DbConfig(
                jdbcUrl =
                    System.getenv("ROADTRIP_DB_URL")
                        ?: "jdbc:postgresql://localhost:5432/roadtrip",
                user = System.getenv("ROADTRIP_DB_USER") ?: "roadtrip",
                password = System.getenv("ROADTRIP_DB_PASSWORD") ?: "roadtrip",
            )
    }
}

// HikariCP for the importer is overkill (single-threaded), but reusing one
// pool config keeps importer + Ktor server symmetric. maxPoolSize = 4 is
// enough for the importer's small concurrent load (mark-and-sweep is one
// transaction).
fun dataSourceFor(
    cfg: DbConfig,
    maxPoolSize: Int = 4,
): HikariDataSource {
    val hk =
        HikariConfig().apply {
            jdbcUrl = cfg.jdbcUrl
            username = cfg.user
            password = cfg.password
            maximumPoolSize = maxPoolSize
            // PostGIS adds dozens of objects to public; keep autocommit on for
            // simple INSERT/UPDATE flows — explicit transactions wrap the
            // mark-and-sweep critical path.
            isAutoCommit = true
        }
    return HikariDataSource(hk)
}

fun migrate(ds: DataSource) {
    // baselineOnMigrate handles the case where the DB was hand-bootstrapped
    // (e.g. yesterday's manual psql validation) before Flyway tracked it.
    Flyway
        .configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate()
}

fun dsl(ds: DataSource): DSLContext = DSL.using(ds, SQLDialect.POSTGRES)
