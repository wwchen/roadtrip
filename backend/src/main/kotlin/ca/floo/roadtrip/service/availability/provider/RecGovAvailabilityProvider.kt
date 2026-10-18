package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.support.RecGovException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import ca.floo.roadtrip.client.recgov.Campsite as RecGovCampsite

private const val RECGOV_BOOKING_HORIZON_DAYS: Int = 180
private const val RECGOV_MAX_POLL_WINDOW_DAYS: Int = 60

class RecGovAvailabilityProvider(
    private val availabilityClient: RecGovAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    override val id: BookingProvider = BookingProvider.RECGOV

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = RECGOV_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = RECGOV_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override suspend fun availability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val recgovId = recgovIdOrThrow(campground)
        // The facility-month payloads cover the campground whatever set out this
        // call, so every campsite's cells are in them already. This map is how a
        // vendor site id becomes a base unit; a site it has no entry for is a row
        // we cannot key, and it is dropped rather than returned unkeyed.
        val campsiteIdByVendorId = campsites.associate { vendorSiteIdFor(it) to it.id }
        return runWithErrorMapping {
            fetchMonths(recgovId, campsiteIdByVendorId, startDate, endDate)
        }
    }

    override fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
    ): String = RecGovBookingUrl.template(vendorSiteIdFor(campsite))

    private suspend fun fetchMonths(
        recgovId: String,
        campsiteIdByVendorId: Map<String, Long>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch =
        coroutineScope {
            val dates = datesInWindow(startDate, endDate)
            val months = monthsCovering(startDate, endDate.minusDays(1))
            val observedAt = Instant.now()
            val payloads =
                months
                    .map { month -> async { availabilityClient.fetchMonth(recgovId, month) } }
                    .awaitAll()

            val merged = mergeCampsites(payloads)
            val catalogSites = campsiteIdByVendorId.keys.associateWith { siteId -> merged[siteId].orEmpty() }
            val observedAtByDate = dates.associateWith { observedAt }

            AvailabilityObservationBatch(
                provider = "recgov",
                startDate = startDate,
                endDate = endDate,
                observations = observationsFromCampsites(catalogSites, dates, observedAtByDate, campsiteIdByVendorId),
                cacheBlock = directFetchCacheBlock(),
                scope = BookingProviderRef.RecGov(facilityId = recgovId),
            )
        }

    private fun recgovIdOrThrow(campground: Campground): String =
        (claimedRef(campground) as? BookingProviderRef.RecGov)?.facilityId
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.bookingProvider ?: "null")

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        mapUpstreamErrors(
            vendorError = { e: RecGovException -> upstreamAvailabilityError(cause = e, httpStatus = e.httpStatus) },
            otherError = { e ->
                // Anything not thrown by the client can still name a rate limit
                // in its message, so that text check stays. Everything else goes
                // through the shared classifier with no typed status: a
                // DNS/connect/socket cause becomes UpstreamUnreachable ("could
                // not reach the booking site") instead of being mislabelled as a
                // vendor 5xx — the exact confusion the incident turned on. A real
                // 5xx has no transport cause, so it still falls through to
                // UpstreamUnavailable.
                val msg = e.message.orEmpty()
                if (msg.contains("429") || msg.contains("rate")) {
                    AvailabilityProviderError.RateLimited(e)
                } else {
                    upstreamAvailabilityError(cause = e, httpStatus = null)
                }
            },
        ) { block() }
}

private fun mergeCampsites(maps: List<Map<String, RecGovCampsite>>): Map<String, Map<String, String>> {
    val out = mutableMapOf<String, MutableMap<String, String>>()
    for (m in maps) {
        for ((id, cs) in m) {
            val target = out.getOrPut(id) { mutableMapOf() }
            for ((rawDate, status) in cs.availabilities) {
                val day = rawDate.substring(0, minOf(10, rawDate.length))
                target[day] = status
            }
        }
    }
    return out
}

private fun datesInWindow(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }

private fun monthsCovering(
    start: LocalDate,
    end: LocalDate,
): List<String> {
    val out = mutableListOf<String>()
    var ym = YearMonth.from(start)
    val endYm = YearMonth.from(end)
    while (!ym.isAfter(endYm)) {
        out += ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        ym = ym.plusMonths(1)
    }
    return out
}

/** Only sites that resolve to a campsite: an unkeyed row is not a base-unit fact. */
private fun observationsFromCampsites(
    merged: Map<String, Map<String, String>>,
    dates: List<LocalDate>,
    observedAtByDate: Map<LocalDate, Instant>,
    campsiteIdsBySiteId: Map<String, Long>,
): List<CampsiteDayObservation> =
    merged.flatMap { (siteId, byDate) ->
        val campsiteId = campsiteIdsBySiteId[siteId] ?: return@flatMap emptyList()
        dates.map { date ->
            CampsiteDayObservation(
                campsiteId = campsiteId,
                date = date,
                observedAt = observedAtByDate[date] ?: Instant.EPOCH,
                status = classifyRecgovStatus(byDate[date.toString()]),
            )
        }
    }

private fun classifyRecgovStatus(raw: String?): AvailabilityStatus {
    val status = raw?.trim()
    if (status.isNullOrEmpty()) return AvailabilityStatus.UNKNOWN
    return when {
        status.equals("null", true) -> AvailabilityStatus.UNKNOWN
        status.equals("Available", true) || status.equals("Open", true) -> AvailabilityStatus.AVAILABLE
        status.equals("Not Reservable", true) -> AvailabilityStatus.FIRST_COME
        status.equals("Closed", true) -> AvailabilityStatus.CLOSED
        status.equals("Reserved", true) || status.equals("Not Available", true) -> AvailabilityStatus.RESERVED
        else -> AvailabilityStatus.UNKNOWN
    }
}

private fun directFetchCacheBlock(): AvailabilityCacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)
