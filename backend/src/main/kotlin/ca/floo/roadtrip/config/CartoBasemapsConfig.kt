package ca.floo.roadtrip.config

data class CartoBasemapsConfig(
    val apiKey: String?,
) {
    companion object {
        private const val API_KEY = "api-key"

        fun fromConfig(config: ConfigSection): CartoBasemapsConfig =
            CartoBasemapsConfig(
                apiKey = config.value(API_KEY),
            )
    }
}
