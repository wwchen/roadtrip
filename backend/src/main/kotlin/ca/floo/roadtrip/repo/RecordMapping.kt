package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CatalogColumnJson
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime

/** Shared jOOQ [Record] projections. Every entity repo maps timestamps and JSONB text the same way. */
internal fun Record.instant(column: String): Instant = get(column, OffsetDateTime::class.java).toInstant()

internal fun Record.nullableInstant(column: String): Instant? = get(column, OffsetDateTime::class.java)?.toInstant()

internal fun parseJsonElement(raw: String): JsonElement = Json.parseToJsonElement(raw)

/**
 * Typed JSONB columns. A column the old jar bound as SQL NULL, and the empty
 * object or array a vendor never filled, all read as absent.
 */
internal inline fun <reified T : Any> decodeListColumn(raw: String?): List<T> =
    if (raw == null) emptyList() else CatalogColumnJson.decodeArray(raw)

internal inline fun <reified T : Any> decodeObjectColumn(raw: String?): T? = if (raw == null) null else CatalogColumnJson.decodeObject(raw)

/**
 * Tolerant decode for the `booking_aliases` bag: [BookingAlias]'s generated
 * serializer would throw on a `provider` id this build's [BookingProvider]
 * enum does not know (a vendor added after this deploy, or by a newer
 * process writing the same row) and fail the whole entity read. Read the raw
 * JSON structurally instead and drop any entry whose provider id, or `ref`,
 * is unrecognized or missing, so a mixed old/new deploy keeps reading.
 */
internal fun decodeBookingAliases(raw: String?): List<BookingAlias> {
    if (raw == null) return emptyList()
    val element = parseJsonElement(raw)
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { entry ->
        val obj = entry.jsonObject
        val provider = obj["provider"]?.jsonPrimitive?.content?.let(BookingProvider::fromIdOrNull) ?: return@mapNotNull null
        val ref = obj["ref"]?.jsonPrimitive?.content ?: return@mapNotNull null
        BookingAlias(provider = provider, ref = ref)
    }
}
