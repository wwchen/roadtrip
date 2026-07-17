package ca.floo.roadtrip.service.availability

enum class TriggerKind(
    val slug: String,
) {
    SLACK_NOTIFY("slack_notify"),
    EMAIL_NOTIFY("email_notify"),
    ATC("atc"),
    ;

    companion object {
        private val bySlug = entries.associateBy { it.slug }

        fun fromSlug(slug: String): TriggerKind? = bySlug[slug]
    }
}
