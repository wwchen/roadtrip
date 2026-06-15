package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.ReservableId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import java.sql.Date
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class ReservableAvailabilityPollerRepo(
    private val ctx: DSLContext,
) {
    private val json = Json

    data class Scope(
        val poiId: Long? = null,
        val reservableId: Long? = null,
        val reservableRid: ReservableId? = null,
    ) {
        init {
            require((poiId != null) xor (reservableId != null)) {
                "exactly one of poiId or reservableId is required"
            }
        }
    }

    data class CreateInput(
        val scope: Scope,
        val reservableFilters: JsonObject,
        val targetDates: List<LocalDate>,
        val minNights: Int,
        val cadenceSec: Int,
        val triggerActions: JsonArray,
        val stopWhenTriggered: Boolean,
    )

    data class PatchInput(
        val status: String? = null,
        val cadenceSec: Int? = null,
        val targetDates: List<LocalDate>? = null,
        val triggerActions: JsonArray? = null,
        val stopWhenTriggered: Boolean? = null,
    )

    data class Poller(
        val id: Long,
        val scope: Scope,
        val reservableFilters: JsonObject,
        val targetDates: List<LocalDate>,
        val minNights: Int,
        val cadenceSec: Int,
        val triggerActions: JsonArray,
        val stopWhenTriggered: Boolean,
        val status: String,
        val lastCheckedAt: OffsetDateTime?,
        val lastTriggeredAt: OffsetDateTime?,
        val nextPollAfter: OffsetDateTime,
        val claimToken: String?,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    )

    data class ClaimedPoller(
        val poller: Poller,
        val claimToken: String,
    )

    fun create(input: CreateInput): Poller {
        require(input.targetDates.isNotEmpty()) { "targetDates must not be empty" }
        val id =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservable_availability_pollers (
                      poi_id, reservable_id, reservable_filters, target_dates,
                      min_nights, cadence_sec, trigger_actions, stop_when_triggered
                    ) VALUES (
                      ?, ?, ?::jsonb, ?::date[], ?, ?, ?::jsonb, ?
                    )
                    RETURNING id
                    """.trimIndent(),
                    input.scope.poiId,
                    input.scope.reservableId,
                    json.encodeToString(input.reservableFilters),
                    input.targetDates.toTypedArray(),
                    input.minNights,
                    input.cadenceSec,
                    json.encodeToString(input.triggerActions),
                    input.stopWhenTriggered,
                )!!
                .get("id", Long::class.java)
        return get(id)!!
    }

    fun list(
        limit: Int = 100,
        offset: Int = 0,
    ): List<Poller> =
        ctx
            .fetch(
                """
                SELECT p.*, r.type, r.vendor, r.vendor_id
                FROM reservable_availability_pollers p
                LEFT JOIN reservables r ON r.id = p.reservable_id
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT ? OFFSET ?
                """.trimIndent(),
                limit,
                offset,
            ).map(::fromRecord)

    fun get(id: Long): Poller? =
        ctx
            .fetchOne(
                """
                SELECT p.*, r.type, r.vendor, r.vendor_id
                FROM reservable_availability_pollers p
                LEFT JOIN reservables r ON r.id = p.reservable_id
                WHERE p.id = ?
                """.trimIndent(),
                id,
            )?.let(::fromRecord)

    fun patch(
        id: Long,
        input: PatchInput,
    ): Poller? {
        val updates = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        input.status?.let {
            updates += "status = ?"
            args += it
        }
        input.cadenceSec?.let {
            updates += "cadence_sec = ?"
            args += it
        }
        input.targetDates?.let {
            updates += "target_dates = ?::date[]"
            args.add(it.toTypedArray())
        }
        input.triggerActions?.let {
            updates += "trigger_actions = ?::jsonb"
            args += json.encodeToString(it)
        }
        input.stopWhenTriggered?.let {
            updates += "stop_when_triggered = ?"
            args += it
        }
        if (updates.isEmpty()) return get(id)
        updates += "updated_at = now()"
        args += id
        ctx.execute(
            "UPDATE reservable_availability_pollers SET ${updates.joinToString(", ")} WHERE id = ?",
            *args.toTypedArray(),
        )
        return get(id)
    }

    fun delete(id: Long): Boolean =
        ctx
            .execute("DELETE FROM reservable_availability_pollers WHERE id = ?", id) > 0

    fun claimDue(
        limit: Int,
        leaseSeconds: Int = 60,
    ): List<ClaimedPoller> {
        val token = UUID.randomUUID().toString()
        return ctx
            .fetch(
                """
                WITH claimed AS (
                  UPDATE reservable_availability_pollers p
                  SET claim_token = ?,
                      claimed_until = now() + (? || ' seconds')::interval,
                      updated_at = now()
                  WHERE p.id IN (
                    SELECT id
                    FROM reservable_availability_pollers
                    WHERE status = 'active'
                      AND next_poll_after <= now()
                      AND (claimed_until IS NULL OR claimed_until < now())
                    ORDER BY next_poll_after ASC, id ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                  )
                  RETURNING p.*
                )
                SELECT claimed.*, r.type, r.vendor, r.vendor_id
                FROM claimed
                LEFT JOIN reservables r ON r.id = claimed.reservable_id
                """.trimIndent(),
                token,
                leaseSeconds,
                limit,
            ).map { record ->
                ClaimedPoller(
                    poller = fromRecord(record),
                    claimToken = token,
                )
            }
    }

    fun completeClaim(
        id: Long,
        claimToken: String,
        triggered: Boolean,
        stopWhenTriggered: Boolean,
    ): Boolean {
        val status = if (triggered && stopWhenTriggered) "done" else "active"
        return ctx
            .execute(
                """
                UPDATE reservable_availability_pollers
                SET status = ?,
                    last_checked_at = now(),
                    last_triggered_at = CASE WHEN ? THEN now() ELSE last_triggered_at END,
                    next_poll_after = now() + (cadence_sec || ' seconds')::interval,
                    claimed_until = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """.trimIndent(),
                status,
                triggered,
                id,
                claimToken,
            ) > 0
    }

    fun failClaim(
        id: Long,
        claimToken: String,
    ): Boolean =
        ctx
            .execute(
                """
                UPDATE reservable_availability_pollers
                SET last_checked_at = now(),
                    next_poll_after = now() + (cadence_sec || ' seconds')::interval,
                    claimed_until = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """.trimIndent(),
                id,
                claimToken,
            ) > 0

    private fun fromRecord(r: org.jooq.Record): Poller {
        val rid =
            if (r.field("type") != null &&
                r.field("vendor") != null &&
                r.field("vendor_id") != null &&
                r.get("type") != null &&
                r.get("vendor") != null &&
                r.get("vendor_id") != null
            ) {
                ReservableId.parse("${r.get("type")}:${r.get("vendor")}:${r.get("vendor_id")}")
            } else {
                null
            }
        return Poller(
            id = requireNotNull(r.long("id")),
            scope =
                Scope(
                    poiId = r.long("poi_id"),
                    reservableId = r.long("reservable_id"),
                    reservableRid = rid,
                ),
            reservableFilters = json.parseToJsonElement(r.get("reservable_filters", JSONB::class.java).data()).jsonObject,
            targetDates =
                (r.get("target_dates") as Array<*>).map {
                    when (it) {
                        is LocalDate -> it
                        is Date -> it.toLocalDate()
                        else -> LocalDate.parse(it.toString())
                    }
                },
            minNights = r.get("min_nights", Int::class.java),
            cadenceSec = r.get("cadence_sec", Int::class.java),
            triggerActions = json.parseToJsonElement(r.get("trigger_actions", JSONB::class.java).data()).jsonArray,
            stopWhenTriggered = r.get("stop_when_triggered", Boolean::class.java),
            status = r.get("status", String::class.java),
            lastCheckedAt = r.get("last_checked_at", OffsetDateTime::class.java),
            lastTriggeredAt = r.get("last_triggered_at", OffsetDateTime::class.java),
            nextPollAfter = r.get("next_poll_after", OffsetDateTime::class.java),
            claimToken = r.get("claim_token", String::class.java),
            createdAt = r.get("created_at", OffsetDateTime::class.java),
            updatedAt = r.get("updated_at", OffsetDateTime::class.java),
        )
    }

    private fun org.jooq.Record.long(name: String): Long? = (get(name) as? Number)?.toLong()
}
