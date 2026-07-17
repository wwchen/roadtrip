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
private const val EMAIL_TO_KEY = "to"
private const val EMAIL_RECIPIENT_SEPARATOR = ","
private val emailRecipientPattern = Regex("""^[^\s@]+@[^\s@]+$""")

internal object WatchTriggerConfig {
    fun validateCreate(input: AvailabilityWatchRepo.CreateInput) {
        validateTriggerIntent(input.triggerKinds, input.triggerConfig)
    }

    fun validateUpdate(input: AvailabilityWatchRepo.UpdateInput) {
        input.triggerKinds?.let { validateTriggerKinds(it) }
        input.triggerConfig?.let { validateConfig(it) }
    }

    fun validateSnapshot(watch: AvailabilityWatchRepo.Watch) {
        validateTriggerIntent(watch.triggerKinds, watch.triggerConfig)
    }

    fun slackChannel(config: JsonObject): String? =
        config
            .section(AvailabilityTriggerKinds.SLACK_NOTIFY)
            ?.nonBlankString(SLACK_CHANNEL_KEY)
            ?: config.nonBlankString(LEGACY_SLACK_CHANNEL_KEY)

    fun emailRecipients(config: JsonObject): List<String> =
        config
            .section(AvailabilityTriggerKinds.EMAIL_NOTIFY)
            ?.nonBlankString(EMAIL_TO_KEY)
            ?.let(::parseEmailRecipients)
            .orEmpty()

    private fun validateTriggerIntent(
        kinds: List<String>,
        config: JsonObject,
    ) {
        validateTriggerKinds(kinds)
        validateConfig(config)
        if (AvailabilityTriggerKinds.EMAIL_NOTIFY in kinds && emailRecipients(config).isEmpty()) {
            throw invalidConfig(
                "trigger_config.${AvailabilityTriggerKinds.EMAIL_NOTIFY}.$EMAIL_TO_KEY is required " +
                    "when ${AvailabilityTriggerKinds.EMAIL_NOTIFY} is enabled",
            )
        }
    }

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
        validateSlackConfig(config)
        validateEmailConfig(config)
    }

    private fun validateSlackConfig(config: JsonObject) {
        val slack = config[AvailabilityTriggerKinds.SLACK_NOTIFY] ?: return
        val slackObject =
            slack as? JsonObject
                ?: throw invalidConfig("trigger_config.${AvailabilityTriggerKinds.SLACK_NOTIFY} must be an object")
        validateOptionalNonBlankString(
            value = slackObject[SLACK_CHANNEL_KEY],
            path = "trigger_config.${AvailabilityTriggerKinds.SLACK_NOTIFY}.$SLACK_CHANNEL_KEY",
        )
    }

    private fun validateEmailConfig(config: JsonObject) {
        val email = config[AvailabilityTriggerKinds.EMAIL_NOTIFY] ?: return
        val emailObject =
            email as? JsonObject
                ?: throw invalidConfig("trigger_config.${AvailabilityTriggerKinds.EMAIL_NOTIFY} must be an object")
        val path = "trigger_config.${AvailabilityTriggerKinds.EMAIL_NOTIFY}.$EMAIL_TO_KEY"
        validateOptionalNonBlankString(
            value = emailObject[EMAIL_TO_KEY],
            path = path,
        )
        emailObject
            .nonBlankString(EMAIL_TO_KEY)
            ?.let { validateEmailRecipients(it, path) }
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

    private fun validateEmailRecipients(
        raw: String,
        path: String,
    ) {
        val recipients = parseEmailRecipients(raw)
        if (recipients.isEmpty() || recipients.any { !emailRecipientPattern.matches(it) }) {
            throw invalidConfig("$path must contain valid email address(es)")
        }
    }

    private fun parseEmailRecipients(raw: String): List<String> =
        raw
            .split(EMAIL_RECIPIENT_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun invalidConfig(message: String): AvailabilityWatchValidationException =
        AvailabilityWatchValidationException(
            error = INVALID_TRIGGER_CONFIG_ERROR,
            message = message,
        )
}

internal fun AvailabilityWatchRepo.Watch.channelOverride(): String? = WatchTriggerConfig.slackChannel(triggerConfig)

internal fun AvailabilityWatchRepo.Watch.emailRecipients(): List<String> = WatchTriggerConfig.emailRecipients(triggerConfig)

private fun JsonObject.section(key: String): JsonObject? {
    val value = this[key] ?: return null
    return runCatching { value.jsonObject }.getOrNull()
}

private fun JsonObject.nonBlankString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
