package ca.floo.roadtrip.config

/**
 * Sandbox-only switches. Must be off (default) in every real deployment.
 *
 * @property assumeUserEnabled resolve every request to a seed user through the
 *   `rt_session=sandbox:<id>` cookie sentinel, with no OIDC flow.
 * @property previewPagesEnabled serve the React pages that are still
 *   mid-migration under `/preview` (see `StaticSiteRoutes`), so a reviewer can look
 *   at an in-progress page against real data. Off everywhere else, because the
 *   pages it exposes are half-built by definition; it goes away when the last page
 *   migrates.
 */
data class SandboxConfig(
    val assumeUserEnabled: Boolean,
    val previewPagesEnabled: Boolean,
) {
    companion object {
        private const val ASSUME_USER_KEY = "assume-user"
        private const val PREVIEW_PAGES_KEY = "preview-pages"
        private const val DEFAULT = "false"

        fun fromConfig(config: ConfigSection): SandboxConfig =
            SandboxConfig(
                assumeUserEnabled = config.valueOrDefault(ASSUME_USER_KEY, DEFAULT).toBoolean(),
                previewPagesEnabled = config.valueOrDefault(PREVIEW_PAGES_KEY, DEFAULT).toBoolean(),
            )
    }
}
