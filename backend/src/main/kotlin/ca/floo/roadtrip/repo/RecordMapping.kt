package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CatalogColumnJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
