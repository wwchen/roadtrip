package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CatalogColumnJson
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * Tolerant decode for `booking_aliases`: anything that is not a usable alias —
 * a non-object entry, an unknown provider id, a missing or blank `ref` — is
 * dropped rather than failing the read, so a newer process writing the same
 * row cannot break a mixed deploy. The column is only CHECKed to be an array.
 */
internal fun decodeBookingAliases(raw: String?): List<BookingAlias> {
    if (raw == null) return emptyList()
    val element = parseJsonElement(raw)
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val provider = obj.text("provider")?.let(BookingProvider::fromIdOrNull) ?: return@mapNotNull null
        val ref = obj.text("ref")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BookingAlias(provider = provider, ref = ref)
    }
}

/** A string field, or null when it is absent, JSON null, or not a primitive. */
private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
