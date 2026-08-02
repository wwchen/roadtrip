package ca.floo.roadtrip.config

/** Sandbox-only switches. Must be off (default) in every real deployment. */
data class SandboxConfig(
    val assumeUserEnabled: Boolean,
) {
    companion object {
        private const val ASSUME_USER_KEY = "assume-user"
        private const val DEFAULT = "false"

        fun fromConfig(config: ConfigSection): SandboxConfig =
            SandboxConfig(
                assumeUserEnabled = config.valueOrDefault(ASSUME_USER_KEY, DEFAULT).toBoolean(),
            )
    }
}
