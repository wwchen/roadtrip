package ca.floo.roadtrip.config

/**
 * Sandbox-only switches. Must be off (default) in every real deployment.
 *
 * @property assumeUserEnabled resolve every request to a seed user through the
 *   `rt_session=sandbox:<id>` cookie sentinel, with no OIDC flow.
 *
 * There used to be a `previewPagesEnabled` here, which served the mid-migration React
 * pages under `/preview` so a reviewer could look at one against real data. The map
 * was the only page it ever carried, and Phase 4e graduated it to `/` — so the flag
 * went with it rather than staying on as config nothing reads.
 */
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
