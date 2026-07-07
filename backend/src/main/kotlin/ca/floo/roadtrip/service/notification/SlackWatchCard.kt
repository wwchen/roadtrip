package ca.floo.roadtrip.service.notification

/**
 * Shared constants for the watch notification card — action ids, attachment
 * colors, primary CTA labels — kept in one place so the outbound renderers
 * ([SlackContentAvailabilityRenderer], [SlackContentWatchStatusRenderer]) and
 * the inbound interactivity handler stay in lockstep. Slack action_ids are
 * effectively an API contract between the two sides; drift here means a click
 * routes to nothing.
 *
 * Attachment colors are the hex bar Slack renders down the attachment's left
 * edge; matched to the design system's `--rt-avail` / `--rt-brand` / `--rt-muted`
 * (they equal the token values, kept as literals because Slack's payload only
 * accepts hex).
 */
internal object SlackWatchCard {
    // Action ids — echoed back in the block_actions payload. Interactive
    // buttons (no url) route by these; URL buttons keep the id so the
    // interactivity endpoint can silently ack the follow-up payload Slack fires
    // even when the redirect is the user-visible effect.
    const val ACTION_WATCH_PAUSE = "watch_pause"
    const val ACTION_WATCH_RESUME = "watch_resume"
    const val ACTION_WATCH_DELETE = "watch_delete"
    const val ACTION_RESERVE_SITE = "reserve_site"
    const val ACTION_OPEN_GRID = "open_grid"
    const val ACTION_OPEN_MAP = "open_map"
    const val ACTION_OPEN_DASHBOARD = "open_dashboard"

    // Attachment bar colors, one per state.
    const val COLOR_AVAIL = "#4cb96a" // openings / DONE
    const val COLOR_WATCHING = "#3b82f6" // WATCHING / UNCHECKED
    const val COLOR_MUTED = "#8a8f96" // PAUSED / STOPPED
}
