package ca.floo.roadtrip.clients.slack

/** Slack native confirm dialog attached to a button. Fires between click and
 *  action — the interactivity payload only arrives when the user confirms. */
data class SlackConfirmSpec(
    val title: String,
    val text: String,
    val confirm: String,
    val deny: String,
    val danger: Boolean = false,
)
