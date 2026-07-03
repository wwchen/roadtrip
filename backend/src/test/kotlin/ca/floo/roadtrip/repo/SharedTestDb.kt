package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Postgres container for the entire backend test JVM — shared by every
 * DB-backed test, so a full run pays for a single container start and a single
 * Flyway migration instead of ~25 of each.
 *
 * Tests run in parallel (JUnit runs test classes concurrently — see
 * src/test/resources/junit-platform.properties), so they can't share one
 * mutable database: they'd truncate each other's rows mid-test. Instead the
 * schema is migrated once into a template database, and each test class gets
 * its OWN database cloned from that template via `CREATE DATABASE … TEMPLATE`
 * (a cheap file copy). Classes are therefore fully isolated while methods
 * within a class stay on one thread and one database.
 *
 * The container starts lazily and is never explicitly stopped; Testcontainers'
 * Ryuk sidecar reaps it (and every per-class database) when the JVM exits.
 */
object SharedTestDb {
    // The one place the postgis image is named — tests must not re-parse it.
    const val IMAGE_NAME = "postgis/postgis:16-3.4"

    // Maintenance DB we stay connected to while issuing CREATE DATABASE; kept
    // separate from the template and the per-class clones so none of them ever
    // has a live connection at clone time.
    private const val ADMIN_DB = "roadtrip_admin"
    private const val TEMPLATE_DB = "roadtrip_template"

    private val dbCounter = AtomicInteger()

    private val container: PostgreSQLContainer<Nothing> by lazy {
        // Docker Desktop 29+ requires API version >=1.44; pin it before any
        // Testcontainers call so docker-java doesn't default to 1.32 and fail
        // to parse the daemon's sanitized info.
        System.setProperty("api.version", "1.44")
        val image = DockerImageName.parse(IMAGE_NAME).asCompatibleSubstituteFor("postgres")
        PostgreSQLContainer<Nothing>(image)
            .apply {
                withDatabaseName(ADMIN_DB)
                withUsername("test")
                withPassword("test")
            }.also { it.start() }
    }

    // Admin pool bound to ADMIN_DB, used only to run `CREATE DATABASE`.
    private val adminDs: HikariDataSource by lazy {
        poolFor(ADMIN_DB, maxPoolSize = 4)
    }

    // Migrate the schema into a template database exactly once. The migrating
    // pool is closed immediately so the template has no live connections and
    // can be used as a `CREATE DATABASE … TEMPLATE` source.
    private val templateReady: Boolean by lazy {
        createDatabaseNamed(TEMPLATE_DB, template = null)
        poolFor(TEMPLATE_DB, maxPoolSize = 2).use { migrate(it) }
        true
    }

    /**
     * A fresh, fully-migrated database cloned from the template, with its own
     * connection pool. Each test class calls this once (via [SharedDbTest]).
     */
    fun createDatabase(): HikariDataSource {
        check(templateReady)
        val name = "t_${dbCounter.incrementAndGet()}"
        createDatabaseNamed(name, template = TEMPLATE_DB)
        return poolFor(name, maxPoolSize = 3)
    }

    /**
     * A fresh, EMPTY (unmigrated) database with its own pool — for the rare
     * test that drives Flyway itself (e.g. migrating to an intermediate target
     * to assert one migration's behaviour). PostGIS is enabled by V1, so a
     * blank database is a valid starting point.
     */
    fun createEmptyDatabase(): HikariDataSource {
        // Touch the container so it's started even if no migrated DB was used.
        val name = "e_${dbCounter.incrementAndGet()}"
        createDatabaseNamed(name, template = null)
        return poolFor(name, maxPoolSize = 2)
    }

    private fun createDatabaseNamed(
        name: String,
        template: String?,
    ) {
        // CREATE DATABASE can't run inside a transaction; Hikari's default
        // autocommit=true satisfies that. Names are internal constants/counters,
        // never user input.
        val sql = if (template != null) "CREATE DATABASE \"$name\" TEMPLATE \"$template\"" else "CREATE DATABASE \"$name\""
        adminDs.connection.use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    private fun poolFor(
        db: String,
        maxPoolSize: Int,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$db"
                username = container.username
                password = container.password
                maximumPoolSize = maxPoolSize
            },
        )
}
