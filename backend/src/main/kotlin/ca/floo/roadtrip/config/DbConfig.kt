package ca.floo.roadtrip.config

data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    companion object {
        private const val DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/roadtrip"
        private const val DEFAULT_USER = "roadtrip"
        private const val DEFAULT_PASSWORD = "roadtrip"

        fun fromProperties(properties: Map<String, String>): DbConfig = fromConfig(ConfigSection(properties).section("roadtrip.db"))

        fun fromConfig(config: ConfigSection): DbConfig =
            DbConfig(
                jdbcUrl = config.valueOrDefault("url", DEFAULT_JDBC_URL),
                user = config.valueOrDefault("user", DEFAULT_USER),
                password = config.valueOrDefault("password", DEFAULT_PASSWORD),
            )
    }
}
