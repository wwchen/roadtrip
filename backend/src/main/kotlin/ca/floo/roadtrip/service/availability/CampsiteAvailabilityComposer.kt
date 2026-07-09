package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.models.api.AvailabilityResponseDto
import ca.floo.roadtrip.models.availability.AvailabilityWindows
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.LocalDate

private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

internal class CampsiteAvailabilityComposer(
    private val targets: AvailabilityTargetResolver,
    private val dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    availability: AvailabilityRepo? = null,
    private val snapshotFreshnessTtl: (AvailabilityProviderId) -> Duration = ::defaultSnapshotFreshnessTtl,
    private val failoverFetcher: FailoverAvailabilityFetcher =
        FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker.fromEnv()),
    private val campsiteProviderRepo: CampsiteProviderRepo? = null,
) {
    private val availabilityLoader = AvailabilityLoader(availability)

    suspend fun availabilityFor(
        campsites: List<Campsite>,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): List<AvailabilityResponseDto> {
        if (campsites.isEmpty()) return emptyList()
        val resolved = campsites.map { targets.resolve(it) ?: throw AvailabilityServiceError.UnknownCampground }
        val byCampsiteId = linkedMapOf<Long, AvailabilityResponseDto>()
        val batcher = CatalogAvailabilityBatcher()
        val results =
            batcher.fetchByGroup(
                targets = resolved,
                windowFor = { context, caps ->
                    val target =
                        dateResolver.resolveWindow(
                            startDate = startDate,
                            endDate = endDate,
                            context = context,
                            bookingHorizonDays = caps.bookingHorizonDays,
                            maxDays = caps.maxPollWindowDays,
                            defaultDays = DEFAULT_AVAILABILITY_DAYS,
                        )
                    val fetch =
                        dateResolver.wideWindow(
                            anchor = target.startDate,
                            context = context,
                            maxPollWindowDays = caps.maxPollWindowDays,
                            bookingHorizonDays = caps.bookingHorizonDays,
                        ) ?: target
                    AvailabilityWindows(target = target, fetch = fetch)
                },
                fetch = { parentRef, provider, rows, windows ->
                    availabilityLoader.loadOrFetch(
                        AvailabilityLoader.Request(
                            metadata = availabilityMetadata(provider.id, parentRef),
                            targets = rows.map { it.toAvailabilityTarget() },
                            startDate = windows.target.startDate,
                            endDate = windows.target.endDate,
                            ttl = snapshotFreshnessTtl(provider.id),
                        ),
                    ) {
                        fetchWithFailover(rows, windows)
                    }
                },
            )
        results.firstOrNull { it.providerError != null }?.let { throw it.providerError!! }
        results.forEach { result ->
            val batch = result.batch ?: return@forEach
            result.campsites.forEach { campsite ->
                val ref = campsite.providerRefForCampsite(result.parentRef)
                val metadata = availabilityMetadata(result.provider.id, ref, campsiteId = campsite.id)
                byCampsiteId[campsite.id] =
                    availabilityResponseFromObservations(
                        batch.copy(
                            observations = batch.observations.filter { it.campsiteId == campsite.id },
                            campgroundId = metadata.campgroundId ?: batch.campgroundId,
                            host = metadata.host ?: batch.host,
                            mapId = metadata.mapId ?: batch.mapId,
                            campsiteId = campsite.id,
                        ),
                    )
            }
        }
        return campsites.map { campsite ->
            byCampsiteId[campsite.id] ?: throw AvailabilityServiceError.NotFound
        }
    }

    /**
     * Runs the group's fetch through [failoverFetcher]: preferred candidate
     * first (with the resolver-picked catalog refs), retryable failures fall
     * through to sibling vendor candidates (looked up via
     * [CampsiteProviderRepo.findCampsiteRefsForCandidate], which keeps the
     * observations anchored to the representative campsite id).
     */
    private suspend fun fetchWithFailover(
        rows: List<ResolvedAvailabilityTarget>,
        windows: AvailabilityWindows,
    ): ca.floo.roadtrip.models.availability.AvailabilityObservationBatch {
        val groupCandidates = rows.first().candidates
        val preferredRefs = rows.map { it.catalogRef }
        val representativeIds = rows.map { it.campsite.id }
        val result =
            failoverFetcher.fetch(
                candidates = groupCandidates,
                campsites = rows.map { it.campsite },
                window = ResolvedDateWindow(windows.fetch.startDate, windows.fetch.endDate),
                translateRefs = { candidate ->
                    if (candidate === groupCandidates.first()) {
                        preferredRefs
                    } else {
                        siblingRefsFor(candidate, representativeIds)
                    }
                },
            )
        return result.batch ?: throw synthesizedError(result.attempts.lastOrNull())
    }

    private fun siblingRefsFor(
        candidate: ProviderCandidate,
        representativeIds: List<Long>,
    ): List<CatalogCampsiteRef> {
        val repo = campsiteProviderRepo ?: return emptyList()
        val vendorSlug = candidate.provider.id.name.lowercase()
        return repo
            .findCampsiteRefsForCandidate(representativeIds, vendorSlug)
            .map { row ->
                // vendorId comes from the sibling row's external_id; the
                // representative id keeps observations landing on the winner-side
                // campsite row.
                CatalogCampsiteRef(
                    campsiteId = row.representativeCampsiteId,
                    vendorId = row.externalId,
                )
            }
    }

    private fun synthesizedError(last: FailoverAvailabilityFetcher.AttemptRecord?): AvailabilityProviderError {
        val message = last?.error ?: NO_CANDIDATES_ERROR
        return when (last?.outcome) {
            FetchOutcome.RATE_LIMITED -> AvailabilityProviderError.RateLimited(RuntimeException(message))
            FetchOutcome.BLOCKED -> AvailabilityProviderError.UpstreamBlocked(RuntimeException(message))
            FetchOutcome.UPSTREAM_5XX,
            FetchOutcome.OK,
            FetchOutcome.OTHER,
            null,
            -> AvailabilityProviderError.UpstreamUnavailable(RuntimeException(message))
        }
    }
}

// Message used when the failover fetcher returns a null batch with no
// attempts (e.g. empty-candidates case). Not user-facing.
private const val NO_CANDIDATES_ERROR: String = "no availability candidates available"

internal fun defaultSnapshotFreshnessTtl(providerId: AvailabilityProviderId): Duration =
    when (providerId) {
        AvailabilityProviderId.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        AvailabilityProviderId.CAMPFLARE -> ApiCacheEntity.CAMPFLARE_AVAILABILITY.defaultTtl
        AvailabilityProviderId.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        AvailabilityProviderId.RESERVEAMERICA -> ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl
        AvailabilityProviderId.RESERVECALIFORNIA -> ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl
    }

private fun ResolvedAvailabilityTarget.toAvailabilityTarget(): AvailabilityLoader.CampsiteTarget =
    AvailabilityLoader.CampsiteTarget(
        dbId = campsite.id,
    )

private fun availabilityMetadata(
    providerId: AvailabilityProviderId,
    ref: ProviderRef,
    campsiteId: Long? = null,
): AvailabilityLoader.Metadata =
    AvailabilityLoader.Metadata(
        provider = providerId.name.lowercase(),
        campgroundId =
            when (ref) {
                is ProviderRef.RecGov -> ref.recgovId
                is ProviderRef.Campflare -> ref.campgroundId
                else -> null
            },
        mapId =
            when (ref) {
                is ProviderRef.Aspira -> ref.mapId.toString()
                is ProviderRef.ReserveCalifornia -> ref.facilityIds.joinToString(",")
                else -> null
            },
        campsiteId = campsiteId,
    )

private fun Campsite.providerRefForCampsite(parentRef: ProviderRef): ProviderRef =
    when (parentRef) {
        is ProviderRef.Aspira ->
            parentRef.copy(
                mapId = aspiraProviderRefLong("mapId") ?: parentRef.mapId,
                resourceLocationId = aspiraProviderRefLong("resourceLocationId") ?: parentRef.resourceLocationId,
            )
        else -> parentRef
    }

private fun Campsite.aspiraProviderRefLong(key: String): Long? =
    (providerRef as? JsonObject)
        ?.get(key)
        ?.jsonPrimitive
        ?.longOrNull
