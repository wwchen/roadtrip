package ca.floo.roadtrip.clients.slack

/** A Slack button element used inside an `actions` block. Buttons render both
 *  in the client UI and (for URL buttons) fire a redirect on click; interactive
 *  buttons additionally fire a `block_actions` payload to the app's Slack
 *  interactivity Request URL, which
 *  [ca.floo.roadtrip.service.notification.SlackInteractivityRoute] verifies and
 *  routes by [actionId] + [value].
 *
 *  Set [url] for URL buttons (opens the URL in the browser); leave it null for
 *  purely-interactive buttons. Both cases still require [actionId] — Slack
 *  makes it mandatory even for URL-only buttons — and it uniquely names the
 *  button across a message.
 *
 *  [style] is limited by Slack to `default` (null), `primary` (green), or
 *  `danger` (red); [confirm] attaches a native Slack confirmation dialog. */
data class SlackButtonSpec(
    val label: String,
    val actionId: String,
    val url: String? = null,
    val value: String? = null,
    val style: Style = Style.DEFAULT,
    val emoji: Boolean = true,
    val confirm: SlackConfirmSpec? = null,
) {
    /** Slack ships only these three visual tiers; there is no brand-blue filled
     *  button. `DEFAULT` renders no `style` field on the wire. */
    enum class Style(
        val wire: String?,
    ) {
        DEFAULT(null),
        PRIMARY("primary"),
        DANGER("danger"),
    }
}
