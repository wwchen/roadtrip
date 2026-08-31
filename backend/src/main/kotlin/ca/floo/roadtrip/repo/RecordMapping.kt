package ca.floo.roadtrip.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime

/** Shared jOOQ [Record] projections. Every entity repo maps timestamps and JSONB text the same way. */
internal fun Record.instant(column: String): Instant = get(column, OffsetDateTime::class.java).toInstant()

internal fun Record.nullableInstant(column: String): Instant? = get(column, OffsetDateTime::class.java)?.toInstant()

internal fun parseJsonElement(raw: String): JsonElement = Json.parseToJsonElement(raw)
