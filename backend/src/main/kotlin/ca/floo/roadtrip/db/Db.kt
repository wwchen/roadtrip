package ca.floo.roadtrip.db

import ca.floo.roadtrip.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

// One pool shape for the importer and the Ktor server. Pool size and
// connection-acquisition timeout come from [DbConfig] rather than literals
// here: they are the two knobs a deployment actually retunes, and
// connectionTimeout in particular is load-bearing for /api/health/ready —
// Hikari's 30-second default lets a readiness request block for half a minute
// on an exhausted pool, which is the failure the probe exists to report.
fun dataSourceFor(cfg: DbConfig): HikariDataSource {
    val hk =
        HikariConfig().apply {
            jdbcUrl = cfg.jdbcUrl
            username = cfg.user
            password = cfg.password
            maximumPoolSize = cfg.maxPoolSize
            connectionTimeout = cfg.connectionTimeout.toMillis()
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
