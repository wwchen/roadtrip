package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val INVALID_TRIGGER_CONFIG_ERROR = "invalid_trigger_config"
private const val LEGACY_SLACK_CHANNEL_KEY = "channel"
private const val SLACK_CHANNEL_KEY = "channel"

internal object WatchTriggerConfig {
    fun validateCreate(input: AvailabilityWatchRepo.CreateInput) {
        validateTriggerKinds(input.triggerKinds)
        validateConfig(input.triggerConfig)
    }

    fun validateUpdate(input: AvailabilityWatchRepo.UpdateInput) {
        input.triggerKinds?.let { validateTriggerKinds(it) }
        input.triggerConfig?.let { validateConfig(it) }
    }

    fun slackChannel(config: JsonObject): String? =
        config
            .section(AvailabilityTriggerKinds.SLACK_NOTIFY)
            ?.nonBlankString(SLACK_CHANNEL_KEY)
            ?: config.nonBlankString(LEGACY_SLACK_CHANNEL_KEY)

    private fun validateTriggerKinds(kinds: List<String>) {
        if (kinds.isEmpty()) {
            throw AvailabilityWatchValidationException(
                error = "invalid_triggers",
                message = "trigger_kinds must be non-empty",
            )
        }
    }

    private fun validateConfig(config: JsonObject) {
        validateOptionalNonBlankString(
            value = config[LEGACY_SLACK_CHANNEL_KEY],
            path = "trigger_config.$LEGACY_SLACK_CHANNEL_KEY",
        )
        val slack = config[AvailabilityTriggerKinds.SLACK_NOTIFY] ?: return
        val slackObject =
            slack as? JsonObject
                ?: throw invalidConfig("trigger_config.${AvailabilityTriggerKinds.SLACK_NOTIFY} must be an object")
        validateOptionalNonBlankString(
            value = slackObject[SLACK_CHANNEL_KEY],
            path = "trigger_config.${AvailabilityTriggerKinds.SLACK_NOTIFY}.$SLACK_CHANNEL_KEY",
        )
    }

    private fun validateOptionalNonBlankString(
        value: JsonElement?,
        path: String,
    ) {
        if (value == null) return
        val primitive =
            value as? JsonPrimitive
                ?: throw invalidConfig("$path must be a non-empty string")
        if (!primitive.isString || primitive.contentOrNull.isNullOrBlank()) {
            throw invalidConfig("$path must be a non-empty string")
        }
    }

    private fun invalidConfig(message: String): AvailabilityWatchValidationException =
        AvailabilityWatchValidationException(
            error = INVALID_TRIGGER_CONFIG_ERROR,
            message = message,
        )
}

internal fun AvailabilityWatchRepo.Watch.channelOverride(): String? = WatchTriggerConfig.slackChannel(triggerConfig)

private fun JsonObject.section(key: String): JsonObject? {
    val value = this[key] as? JsonElement ?: return null
    return runCatching { value.jsonObject }.getOrNull()
}

private fun JsonObject.nonBlankString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
