package ca.floo.roadtrip.service.notification

import java.time.LocalDate

/**
 * One hydrated campsite opening handed to
 * [SlackNotificationService.sendWatchOpenings]. The caller (the availability
 * dispatcher) resolves everything the alert renders — the site's display label,
 * loop, and type, the parent campground (id + name), and the provider booking
 * URL — so the notification layer only formats and never reaches back into the
 * availability or reservation domain.
 *
 * [campgroundId] is the parent POI id; it (not [campground], which may be null
 * for an un-hydrated POI) is what distinguishes campgrounds, so a multi-park
 * alert is detected reliably even when a name is missing.
 */
data class WatchOpening(
    val label: String,
    val loop: String?,
    val siteType: String?,
    val date: LocalDate,
    val campgroundId: Long?,
    val campground: String?,
    val bookingUrl: String?,
    val vendor: String? = null,
)
