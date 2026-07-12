package ca.floo.roadtrip.config

data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): DbConfig =
            DbConfig(
                jdbcUrl = env["ROADTRIP_DB_URL"] ?: DEFAULT_JDBC_URL,
                user = env["ROADTRIP_DB_USER"] ?: DEFAULT_USER,
                password = env["ROADTRIP_DB_PASSWORD"] ?: DEFAULT_PASSWORD,
            )

        private const val DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/roadtrip"
        private const val DEFAULT_USER = "roadtrip"
        private const val DEFAULT_PASSWORD = "roadtrip"
    }
}
