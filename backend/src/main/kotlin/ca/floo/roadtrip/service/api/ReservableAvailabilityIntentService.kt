package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.api.ReservableAvailabilityFiltersSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityQueryRequestSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityQueryResponseSchema
import ca.floo.roadtrip.models.api.ReservableAvailabilityResultSchema
import ca.floo.roadtrip.models.api.ReservableSchema
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityRunRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.ProviderRefParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

internal class ReservableAvailabilityIntentService(
    private val providerRefs: CampsiteProviderRepo,
    private val bookingProviders: BookingProviderRegistry,
    private val reservables: ReservableRepo,
    private val pois: PoiServingRepo,
    private val availabilityLogs: ReservableAvailabilityLogRepo,
    private val runs: ReservableAvailabilityRunRepo,
) {
    private val json = Json { encodeDefaults = true }
    private val fetches = ReservableAvailabilityFetchService(availabilityLogs)

    data class Execution(
        val response: ReservableAvailabilityQueryResponseSchema,
        val run: ReservableAvailabilityRunRepo.Run,
    )

    suspend fun execute(
        request: ReservableAvailabilityQueryRequestSchema,
        sourceKind: String = "query",
        pollerId: Long? = null,
    ): Execution {
        validate(request)
        val intentPayload = json.encodeToJsonElement(request).jsonObject
        val run = runs.start(sourceKind, pollerId, intentPayload)
        var candidateCount = 0
        var logCount = 0
        try {
            val start = LocalDate.parse(request.startDate)
            val candidates = resolveCandidates(request)
            candidateCount = candidates.size
            val results = mutableListOf<ReservableAvailabilityResultSchema>()
            val candidateSchemas = mutableListOf<ReservableSchema>()

            for (candidate in candidates) {
                val provider = resolveProvider(candidate, request.scope.poiId) ?: continue
                val result =
                    fetches.fetchAndLog(
                        ReservableAvailabilityFetchService.Request(
                            reservableRid = candidate.reservable.rid.encode(),
                            provider = provider.provider,
                            ref = provider.ref,
                            vendorId = candidate.reservable.rid.vendorId,
                            start = start,
                            days = request.days,
                            minNights = request.minNights,
                            force = request.force,
                            runId = run.id,
                        ),
                    )
                logCount += result.logCount
                candidateSchemas += candidate.reservable.toSchema(candidate.poiIds)
                results += result.response.toIntentResult(candidate.reservable.toSchema(candidate.poiIds))
            }

            val completed = runs.complete(run.id, candidateCount, logCount)
            return Execution(
                response =
                    ReservableAvailabilityQueryResponseSchema(
                        runId = completed.id,
                        observedAt = completed.startedAt.toString(),
                        candidateCount = candidateCount,
                        logCount = logCount,
                        candidates = candidateSchemas,
                        results = results,
                    ),
                run = completed,
            )
        } catch (e: Exception) {
            runs.fail(run.id, "${e.javaClass.simpleName}: ${e.message ?: ""}", candidateCount, logCount)
            throw e
        }
    }

    fun pollerIntent(
        scope: ca.floo.roadtrip.models.api.ReservableAvailabilityScopeSchema,
        filters: ReservableAvailabilityFiltersSchema,
        targetDates: List<LocalDate>,
        minNights: Int,
        force: Boolean = false,
    ): ReservableAvailabilityQueryRequestSchema {
        val start = targetDates.minOrNull() ?: throw BadAvailabilityIntent("bad_target_dates", "target_dates must not be empty")
        val end = targetDates.maxOrNull()!!
        return ReservableAvailabilityQueryRequestSchema(
            scope = scope,
            reservableFilters = filters,
            startDate = start.toString(),
            days = (end.toEpochDay() - start.toEpochDay() + 1).toInt(),
            minNights = minNights,
            force = force,
        )
    }

    fun filtersToJson(filters: ReservableAvailabilityFiltersSchema): JsonObject =
        buildJsonObject {
            if (filters.type.isNotEmpty()) put("type", JsonArray(filters.type.map(::JsonPrimitive)))
            if (filters.vendor.isNotEmpty()) put("vendor", JsonArray(filters.vendor.map(::JsonPrimitive)))
            if (filters.vendorId.isNotEmpty()) put("vendor_id", JsonArray(filters.vendorId.map(::JsonPrimitive)))
            if (filters.name.isNotEmpty()) put("name", JsonArray(filters.name.map(::JsonPrimitive)))
            if (filters.loop.isNotEmpty()) put("loop", JsonArray(filters.loop.map(::JsonPrimitive)))
            if (filters.siteType.isNotEmpty()) put("site_type", JsonArray(filters.siteType.map(::JsonPrimitive)))
            filters.raw?.let { put("raw", it) }
        }

    fun filtersFromJson(raw: JsonObject): ReservableAvailabilityFiltersSchema {
        fun strings(key: String): List<String> {
            val value = raw[key] ?: return emptyList()
            return when (value) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(value.content)
                else -> emptyList()
            }
        }
        return ReservableAvailabilityFiltersSchema(
            type = strings("type"),
            vendor = strings("vendor"),
            vendorId = strings("vendor_id"),
            name = strings("name"),
            loop = strings("loop"),
            siteType = strings("site_type"),
            raw = raw["raw"],
        )
    }

    private fun validate(request: ReservableAvailabilityQueryRequestSchema) {
        val hasPoi = request.scope.poiId != null
        val hasRid = !request.scope.rid.isNullOrBlank()
        if (hasPoi == hasRid) throw BadAvailabilityIntent("bad_scope", "exactly one of scope.poi_id or scope.rid is required")
        if (request.days !in 1..60) throw BadAvailabilityIntent("bad_days", "days must be between 1 and 60")
        if (request.minNights !in 1..31) throw BadAvailabilityIntent("bad_min_nights", "min_nights must be between 1 and 31")
        runCatching { LocalDate.parse(request.startDate) }
            .getOrElse { throw BadAvailabilityIntent("bad_start_date", "start_date must be YYYY-MM-DD") }
    }

    private fun resolveCandidates(request: ReservableAvailabilityQueryRequestSchema): List<Candidate> {
        request.scope.rid?.takeIf { it.isNotBlank() }?.let { rawRid ->
            val rid = ReservableId.parse(rawRid) ?: throw BadAvailabilityIntent("bad_rid", rawRid)
            val reservable = reservables.findByRid(rid) ?: throw BadAvailabilityIntent("not_found", rawRid)
            val poiIds = reservables.poiIdsForReservable(reservable.id)
            return listOf(Candidate(reservable, poiIds)).filter { it.reservable.matches(request.reservableFilters) }
        }

        val poiId = request.scope.poiId ?: throw BadAvailabilityIntent("bad_scope", "scope.poi_id is required")
        pois.fetchPoiById(poiId) ?: throw BadAvailabilityIntent("not_found", "poi_id=$poiId")
        return reservables
            .findByPoi(poiId, type = null)
            .filter { it.matches(request.reservableFilters) }
            .map { Candidate(it, listOf(poiId)) }
    }

    private fun resolveProvider(
        candidate: Candidate,
        preferredPoiId: Long?,
    ): ProviderResolution? {
        val poiIds =
            if (preferredPoiId != null && preferredPoiId in candidate.poiIds) {
                listOf(preferredPoiId)
            } else {
                candidate.poiIds
            }
        val rowsById = providerRefs.findProviderRefs(poiIds)
        for (poiId in poiIds) {
            val row = rowsById[poiId] ?: continue
            val provider = bookingProviders.forPoi(row) ?: continue
            val ref = ProviderRefParser.parse(row.providerRefJson) ?: continue
            return ProviderResolution(provider, ref)
        }
        return null
    }

    private data class Candidate(
        val reservable: Reservable,
        val poiIds: List<Long>,
    )

    private data class ProviderResolution(
        val provider: BookingProvider,
        val ref: ca.floo.roadtrip.models.ProviderRef,
    )

    private fun AvailabilityResponseDto.toIntentResult(reservable: ReservableSchema): ReservableAvailabilityResultSchema =
        ReservableAvailabilityResultSchema(
            reservable = reservable,
            matchingStarts = availability.filter { it.status == "available" && it.availableCount > 0 }.map { it.date },
            partialStarts = availability.filter { it.status == "partial" }.map { it.date },
        )

    private fun Reservable.matches(filters: ReservableAvailabilityFiltersSchema): Boolean {
        fun containsOrEmpty(
            values: List<String>,
            actual: String?,
        ): Boolean = values.isEmpty() || actual in values
        val typeOk =
            filters.type.isEmpty() ||
                filters.type.mapNotNull(ReservableType::parse).any { it == rid.type }
        return typeOk &&
            containsOrEmpty(filters.vendor, rid.vendor) &&
            containsOrEmpty(filters.vendorId, rid.vendorId) &&
            containsOrEmpty(filters.name, name) &&
            containsOrEmpty(filters.loop, loop) &&
            containsOrEmpty(filters.siteType, siteType) &&
            jsonContains(raw, filters.raw)
    }

    private fun jsonContains(
        actual: JsonElement?,
        expected: JsonElement?,
    ): Boolean {
        if (expected == null) return true
        if (actual == null) return false
        if (expected !is JsonObject) return actual == expected
        val actualObj = actual as? JsonObject ?: return false
        return expected.all { (key, value) -> jsonContains(actualObj[key], value) }
    }

    private fun Reservable.toSchema(poiIds: List<Long>): ReservableSchema =
        ReservableSchema(
            rid = rid.encode(),
            type = rid.type.encode(),
            vendor = rid.vendor,
            vendorId = rid.vendorId,
            name = name,
            loop = loop,
            siteType = siteType,
            poiIds = poiIds,
            raw = raw,
        )
}

class BadAvailabilityIntent(
    val error: String,
    message: String,
) : IllegalArgumentException(message)
