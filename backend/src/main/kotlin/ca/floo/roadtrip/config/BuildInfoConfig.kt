package ca.floo.roadtrip.config

/** Deploy-time build identity, injected via env vars (see application.yaml). */
data class BuildInfoConfig(
    val env: String,
    val sha: String,
    val branch: String,
) {
    companion object {
        private const val ENV_KEY = "env"
        private const val SHA_KEY = "sha"
        private const val BRANCH_KEY = "branch"
        private const val DEFAULT_ENV = "local"
        private const val UNKNOWN = "unknown"

        fun fromConfig(config: ConfigSection): BuildInfoConfig =
            BuildInfoConfig(
                env = config.valueOrDefault(ENV_KEY, DEFAULT_ENV),
                sha = config.valueOrDefault(SHA_KEY, UNKNOWN),
                branch = config.valueOrDefault(BRANCH_KEY, UNKNOWN),
            )
    }
}
