package ca.floo.campsite.recgov.booker.db

import ca.floo.campsite.recgov.booker.domain.Match
import ca.floo.roadtrip.db.generated.tables.references.ALERTS
import ca.floo.roadtrip.db.generated.tables.references.MATCHES
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

class MatchRepo(
    private val ctx: DSLContext,
) {
    data class CreateInput(
        val alertId: Long,
        val campgroundId: String,
        val campsiteId: String,
        val site: String?,
        val loop: String?,
        val campsiteType: String?,
        val availableDates: List<String>,
        val firstDate: String,
        val nights: Int,
    )

    /** Returns the new match id, or null if a duplicate exists within the last hour (matches legacy dedup). */
    fun create(
        input: CreateInput,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Long? {
        val oneHourAgo = now.minusHours(1)
        val existing =
            ctx
                .selectFrom(MATCHES)
                .where(MATCHES.ALERT_ID.eq(input.alertId))
                .and(MATCHES.CAMPSITE_ID.eq(input.campsiteId))
                .and(MATCHES.FIRST_DATE.eq(LocalDate.parse(input.firstDate)))
                .and(MATCHES.FOUND_AT.gt(oneHourAgo))
                .fetchAny()
        if (existing != null) return null

        val rec = ctx.newRecord(MATCHES)
        rec.alertId = input.alertId
        rec.campgroundId = input.campgroundId
        rec.campsiteId = input.campsiteId
        rec.campsiteSite = input.site
        rec.campsiteLoop = input.loop
        rec.campsiteType = input.campsiteType
        rec.availableDates = jsonbList(input.availableDates)
        rec.firstDate = LocalDate.parse(input.firstDate)
        rec.nights = input.nights
        rec.store()
        return rec.id
    }

    fun get(id: Long): Match? =
        ctx
            .select()
            .from(MATCHES)
            .leftJoin(ALERTS)
            .on(MATCHES.ALERT_ID.eq(ALERTS.ID))
            .where(MATCHES.ID.eq(id))
            .fetchOne()
            ?.toDomain()

    fun list(
        limit: Int,
        alertId: Long?,
    ): List<Match> {
        var q =
            ctx
                .select()
                .from(MATCHES)
                .leftJoin(ALERTS)
                .on(MATCHES.ALERT_ID.eq(ALERTS.ID))
                .where(MATCHES.DISMISSED_AT.isNull)
        if (alertId != null) q = q.and(MATCHES.ALERT_ID.eq(alertId))
        return q.orderBy(MATCHES.FOUND_AT.desc()).limit(limit).map { it.toDomain() }
    }

    fun markNotified(id: Long) {
        ctx
            .update(MATCHES)
            .set(MATCHES.NOTIFIED, true)
            .where(MATCHES.ID.eq(id))
            .execute()
    }

    fun softDelete(
        id: Long,
        now: OffsetDateTime = OffsetDateTime.now(),
    ) {
        ctx
            .update(MATCHES)
            .set(MATCHES.DISMISSED_AT, now)
            .where(MATCHES.ID.eq(id))
            .execute()
    }

    /** Returns the updated match if claim succeeded; null if already claimed/dismissed or not found. */
    fun claim(
        id: Long,
        companion: String,
        leaseDuration: Duration,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Match? {
        // Atomic: only succeed if claimed_by IS NULL and not dismissed and not resulted.
        val updated =
            ctx
                .update(MATCHES)
                .set(MATCHES.CLAIMED_BY, companion)
                .set(MATCHES.CLAIMED_AT, now)
                .set(MATCHES.LEASE_EXPIRES, now.plus(leaseDuration))
                .where(MATCHES.ID.eq(id))
                .and(MATCHES.CLAIMED_BY.isNull)
                .and(MATCHES.DISMISSED_AT.isNull)
                .and(MATCHES.RESULT_AT.isNull)
                .execute()
        return if (updated > 0) get(id) else null
    }

    fun result(
        id: Long,
        cartAdded: Boolean,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Match? {
        val updated =
            ctx
                .update(MATCHES)
                .set(MATCHES.CART_ADDED, cartAdded)
                .set(MATCHES.RESULT_AT, now)
                .set(MATCHES.LEASE_EXPIRES, null as OffsetDateTime?)
                .where(MATCHES.ID.eq(id))
                .and(MATCHES.CLAIMED_BY.isNotNull)
                .and(MATCHES.RESULT_AT.isNull)
                .execute()
        return if (updated > 0) get(id) else null
    }

    /** Clears claim + result so a match can be re-attempted by ATC. */
    fun clearClaim(id: Long) {
        ctx
            .update(MATCHES)
            .setNull(MATCHES.CLAIMED_BY)
            .setNull(MATCHES.CLAIMED_AT)
            .setNull(MATCHES.LEASE_EXPIRES)
            .setNull(MATCHES.RESULT_AT)
            .setNull(MATCHES.CART_ADDED)
            .where(MATCHES.ID.eq(id))
            .execute()
    }

    /**
     * The single planner query for ATC orchestration. Returns the next match
     * that should be ATC'd, or null. Encodes every rule:
     *
     *   - alert.status = 'active' (not paused/done)
     *   - alert.auto_cart = true (user opted into automation)
     *   - match has not been dismissed, has not been resulted, is not currently claimed
     *   - the alert has no other in-flight ATC (claimed but not resulted, lease unexpired)
     *   - oldest match first (FIFO by found_at)
     *
     * Read-only — does not mutate. Companion still calls /claim separately to
     * atomically lock the row before running ATC.
     *
     * The NOT EXISTS subquery is what enforces "one ATC at a time per alert" —
     * it prevents handing out work for an alert that already has a worker on
     * it. When the worker finishes (result or lease expiry), the in-flight
     * row falls out of the NOT EXISTS set and the next match becomes pickable.
     */
    fun nextWorkItem(now: OffsetDateTime = OffsetDateTime.now()): Match? {
        val m2 = MATCHES.`as`("m2")
        val sql =
            ctx
                .select()
                .from(MATCHES)
                .leftJoin(ALERTS)
                .on(MATCHES.ALERT_ID.eq(ALERTS.ID))
                .where(ALERTS.STATUS.eq("active"))
                .and(ALERTS.AUTO_CART.eq(true))
                .and(MATCHES.DISMISSED_AT.isNull)
                .and(MATCHES.RESULT_AT.isNull)
                .and(MATCHES.CLAIMED_BY.isNull)
                .andNotExists(
                    org.jooq.impl.DSL
                        .selectOne()
                        .from(m2)
                        .where(m2.ALERT_ID.eq(MATCHES.ALERT_ID))
                        .and(m2.CLAIMED_BY.isNotNull)
                        .and(m2.RESULT_AT.isNull)
                        .and(m2.LEASE_EXPIRES.isNull.or(m2.LEASE_EXPIRES.gt(now))),
                ).orderBy(MATCHES.FOUND_AT.asc())
                .limit(1)
        return sql.fetchOne()?.toDomain()
    }

    /** Releases leases that expired without a result. Returns the released matches. */
    fun sweepExpiredLeases(now: OffsetDateTime = OffsetDateTime.now()): List<Match> {
        val expired =
            ctx
                .selectFrom(MATCHES)
                .where(MATCHES.LEASE_EXPIRES.lt(now))
                .and(MATCHES.RESULT_AT.isNull)
                .fetch()
        if (expired.isEmpty()) return emptyList()
        val ids = expired.map { it.id!! }
        ctx
            .update(MATCHES)
            .set(MATCHES.CLAIMED_BY, null as String?)
            .set(MATCHES.CLAIMED_AT, null as OffsetDateTime?)
            .set(MATCHES.LEASE_EXPIRES, null as OffsetDateTime?)
            .where(MATCHES.ID.`in`(ids))
            .execute()
        return ids.mapNotNull { get(it) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Record.toDomain(): Match {
    val id = (this.get("id", Long::class.java))
    val alertId = this.get("alert_id", Long::class.java)
    val campgroundId = this.get("campground_id", String::class.java)
    val campsiteId = this.get("campsite_id", String::class.java)
    val campsiteSite = this.get("campsite_site", String::class.java)
    val campsiteLoop = this.get("campsite_loop", String::class.java)
    val campsiteType = this.get("campsite_type", String::class.java)
    val availableDates = parseStringList(this.get("available_dates") as JSONB?)
    val firstDate = this.get("first_date", LocalDate::class.java)
    val nights = this.get("nights", Int::class.javaObjectType)
    val foundAt = this.get("found_at", OffsetDateTime::class.java)
    val notified = this.get("notified", Boolean::class.javaObjectType) ?: false
    val claimedBy = this.get("claimed_by", String::class.java)
    val claimedAt = this.get("claimed_at", OffsetDateTime::class.java)
    val leaseExpires = this.get("lease_expires", OffsetDateTime::class.java)
    val cartAdded = this.get("cart_added", Boolean::class.javaObjectType)
    val resultAt = this.get("result_at", OffsetDateTime::class.java)
    val dismissedAt = this.get("dismissed_at", OffsetDateTime::class.java)
    // From left-joined alerts table — lookup by aliased column. Field collisions
    // (campground_id appears on both tables) resolve to matches' value first.
    val alertCampgroundName =
        try {
            this.get(ALERTS.CAMPGROUND_NAME)
        } catch (_: Exception) {
            null
        }
    val alertStart =
        try {
            this.get(ALERTS.START_DATE)?.toString()
        } catch (_: Exception) {
            null
        }
    val alertEnd =
        try {
            this.get(ALERTS.END_DATE)?.toString()
        } catch (_: Exception) {
            null
        }

    return Match(
        id = id,
        alertId = alertId,
        campgroundId = campgroundId,
        campsiteId = campsiteId,
        campsiteSite = campsiteSite,
        campsiteLoop = campsiteLoop,
        campsiteType = campsiteType,
        availableDates = availableDates,
        firstDate = firstDate.toString(),
        nights = nights,
        foundAt = foundAt.toString(),
        notified = notified,
        claimedBy = claimedBy,
        claimedAt = claimedAt?.toString(),
        leaseExpires = leaseExpires?.toString(),
        cartAdded = cartAdded,
        resultAt = resultAt?.toString(),
        dismissedAt = dismissedAt?.toString(),
        campgroundName = alertCampgroundName,
        alertStart = alertStart,
        alertEnd = alertEnd,
    )
}
