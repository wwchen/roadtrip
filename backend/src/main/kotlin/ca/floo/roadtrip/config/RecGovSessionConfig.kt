package ca.floo.roadtrip.config

data class RecGovSessionConfig(
    val recaccountJson: String?,
) {
    companion object {
        fun fromConfig(config: ConfigSection): RecGovSessionConfig =
            RecGovSessionConfig(
                recaccountJson = config.rawValue("recaccount")?.trim()?.takeIf { it.isNotEmpty() },
            )
    }
}
