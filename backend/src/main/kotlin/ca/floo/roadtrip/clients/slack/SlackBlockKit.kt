package ca.floo.roadtrip.clients.slack

import kotlinx.serialization.Serializable

/**
 * Minimal Slack Block Kit payload model — only the block types we send. Callers
 * build these through [SlackBlocks]; [SlackClient] serializes them onto
 * `chat.postMessage`. Keeping the Block Kit `type` vocabulary here (not at call
 * sites) is the one place that knows Slack's wire shape.
 */
@Serializable
data class SlackBlockDto(
    val type: String,
    val text: SlackTextDto? = null,
    val fields: List<SlackTextDto>? = null,
)

@Serializable
data class SlackTextDto(
    val type: String,
    val text: String,
    val emoji: Boolean? = null,
)

/** A deep-link rendered inline in a section as a Slack mrkdwn hyperlink: a
 *  [label], the [url] it opens, and whether it's [emphasized] (bolded) as the
 *  primary call-to-action. Callers pass these to [SlackBlocks.links] rather than
 *  building link markup directly.
 *
 *  Links, not Block Kit `button` elements: a `button` fires a `block_actions`
 *  interaction payload to the app's interactivity Request URL on every click —
 *  even a URL-only button — so Slack flags the message ("this app is not
 *  configured to handle interactive responses") whenever no such URL is
 *  configured. This app is outbound-only (`chat.postMessage`, no inbound
 *  interactivity endpoint), so deep-links render as hyperlinks that open in the
 *  browser with no interaction callback. */
data class LinkSpec(
    val label: String,
    val url: String,
    val emphasized: Boolean = false,
)

/** Semantic builders so callers express intent (header, fields, section,
 *  links) instead of sprinkling Block Kit `type` strings. */
object SlackBlocks {
    /** Max links rendered in one section; longer lists must be chunked across
     *  sections so a section's mrkdwn text stays well under Slack's limit. */
    const val LINKS_MAX_PER_SECTION = 8

    /** Separator between inline links within a section. */
    private const val LINK_SEPARATOR = "   ·   "

    fun header(text: String): SlackBlockDto = SlackBlockDto(type = "header", text = plain(text))

    /** A section rendered as a 2-column field grid (Slack lays fields out in pairs). */
    fun fields(fields: List<String>): SlackBlockDto = SlackBlockDto(type = "section", fields = fields.map(::mrkdwn))

    fun section(text: String): SlackBlockDto = SlackBlockDto(type = "section", text = mrkdwn(text))

    /** A section of inline mrkdwn hyperlinks. Caller must keep [links] within
     *  [LINKS_MAX_PER_SECTION]; chunk longer lists into multiple sections. */
    fun links(links: List<LinkSpec>): SlackBlockDto = section(links.joinToString(LINK_SEPARATOR, transform = ::renderLink))

    /** A section holding a single emphasized deep-link — the primary CTA. */
    fun primaryLink(
        label: String,
        url: String,
    ): SlackBlockDto = links(listOf(LinkSpec(label, url, emphasized = true)))

    /** Slack mrkdwn hyperlink: `<url|label>`, bolded when the link is the CTA. */
    private fun renderLink(link: LinkSpec): String {
        val markup = "<${link.url}|${link.label}>"
        return if (link.emphasized) "*$markup*" else markup
    }

    private fun plain(text: String): SlackTextDto = SlackTextDto(type = "plain_text", text = text, emoji = true)

    private fun mrkdwn(text: String): SlackTextDto = SlackTextDto(type = "mrkdwn", text = text)
}
