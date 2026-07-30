package ca.floo.roadtrip.config

import java.time.Duration

data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
    val connectionTimeout: Duration = defaultConnectionTimeout,
) {
    companion object {
        private const val DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/roadtrip"
        private const val DEFAULT_USER = "roadtrip"
        private const val DEFAULT_PASSWORD = "roadtrip"
        private const val MAX_POOL_SIZE_KEY = "max-pool-size"
        private const val CONNECTION_TIMEOUT_KEY = "connection-timeout"

        /**
         * HikariCP for the importer is overkill (single-threaded), but reusing
         * one pool config keeps importer + Ktor server symmetric. Four is
         * enough for the importer's small concurrent load (mark-and-sweep is
         * one transaction), and tunable for the server, whose concurrency
         * depends on the deployment.
         */
        const val DEFAULT_MAX_POOL_SIZE = 4

        /**
         * How long a caller waits to be handed a pooled connection before
         * Hikari gives up. Hikari's own default is 30 seconds, which is a
         * readiness outage in its own right: `/api/health/ready` exists to
         * report pool exhaustion *fast*, and a jOOQ `queryTimeout` cannot bound
         * it because that clock only starts once a connection has been
         * acquired. Bounded here so a wedged pool answers 503 in seconds
         * instead of stacking up 30-second requests. Operationally tunable — a
         * deployment with a larger pool or slower storage may want more.
         */
        val defaultConnectionTimeout: Duration = Duration.ofSeconds(3)

        fun fromConfig(config: ConfigSection): DbConfig =
            DbConfig(
                jdbcUrl = config.valueOrDefault("url", DEFAULT_JDBC_URL),
                user = config.valueOrDefault("user", DEFAULT_USER),
                password = config.valueOrDefault("password", DEFAULT_PASSWORD),
                maxPoolSize = config.value(MAX_POOL_SIZE_KEY)?.toInt() ?: DEFAULT_MAX_POOL_SIZE,
                connectionTimeout = config.duration(CONNECTION_TIMEOUT_KEY, defaultConnectionTimeout),
            )
    }
}
