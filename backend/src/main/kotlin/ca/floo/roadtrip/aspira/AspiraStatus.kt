package ca.floo.roadtrip.aspira

/**
 * Aspira NextGen returns availability as integer codes per (map, day). The
 * codes are not publicly documented, so this mapping is observed across
 * multiple parks (Banff peak summer, Yoho, Glacier, winter dates) on
 * 2026-06-07. If Aspira ever introduces a new code we'll see it as
 * [UNKNOWN] in [classify] and surface it as `partial` to be safe.
 *
 * Mapping rationale:
 *   1, 2  → AVAILABLE   — at least one site bookable, no constraints
 *   3, 7  → PARTIAL     — mixed: some sub-areas/sites avail, others not
 *   6     → MOSTLY_BOOKED — observed when peak-summer Banff has only a few
 *                          slots left; treat as `partial` for the FE
 *   0     → CLOSED      — observed at park-level when sub-areas are all
 *                          closed (treated as no-data / not bookable)
 *   5     → CLOSED      — explicit closed (winter, end-of-season)
 *   else  → UNKNOWN     — unfamiliar code, surfaced as `partial`
 *
 * The FE contract from /api/campsite/availability/{recgov_id} uses the
 * vocabulary `available | partial | booked | closed`. We map onto the same
 * vocabulary for shape compatibility — the drawer's render path is
 * identical regardless of provider.
 */
object AspiraStatus {
    const val NO_DATA = 0
    const val AVAILABLE = 1
    const val LIMITED = 2
    const val PARTIAL = 3
    const val UNAVAILABLE = 5
    const val MOSTLY_BOOKED = 6
    const val MIXED = 7

    /** Map an Aspira integer code to the FE's day-status vocabulary. */
    fun classify(code: Int): String =
        when (code) {
            AVAILABLE, LIMITED -> "available"
            PARTIAL, MIXED, MOSTLY_BOOKED -> "partial"
            UNAVAILABLE, NO_DATA -> "closed"
            else -> "partial" // surface unknowns as partial; they're rare and visible-but-not-misleading
        }
}
