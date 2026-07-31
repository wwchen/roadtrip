package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.api.availabilityErrorDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
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
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val recgovId = recgovIdOrThrow(campground)
        return runWithErrorMapping {
            fetchAvailability(recgovId, startDate, endDate)
        }
    }

    override suspend fun catalogAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        if (campsites.isEmpty()) {
            return availability(campground, startDate, endDate)
        }
        val recgovId = recgovIdOrThrow(campground)
        val campsiteIdByVendorId = campsites.associate { it.recgovSiteId() to it.id }
        return runWithErrorMapping {
            fetchCatalogAvailability(recgovId, campsiteIdByVendorId, startDate, endDate)
        }
    }

    override fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
    ): String = RecGovBookingUrl.template(campsite.recgovSiteId())

    private suspend fun fetchAvailability(
        recgovId: String,
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
            val observedAtByDate = dates.associateWith { observedAt }

            AvailabilityObservationBatch(
                provider = "recgov",
                startDate = startDate,
                endDate = endDate,
                observations = observationsFromCampsites(merged, dates, observedAtByDate),
                seasonBlock = inferReopenDate(merged, startDate),
                cacheBlock = directFetchCacheBlock(),
                campgroundId = recgovId,
            )
        }

    private suspend fun fetchCatalogAvailability(
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
                seasonBlock = inferReopenDate(catalogSites, startDate),
                cacheBlock = directFetchCacheBlock(),
                campgroundId = recgovId,
            )
        }

    private fun recgovIdOrThrow(campground: Campground): String {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull)
        val ref = provider?.let { campground.bookingProviderRef?.let { r -> BookingProviderRef.parse(it, r) } }
        return (ref as? BookingProviderRef.RecGov)?.facilityId
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.bookingProvider ?: "null")
    }

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // rec.gov signals a rate limit through the exception message (the
            // client bakes the status into the string), so that stays a text
            // check. Everything else goes through the shared classifier with
            // no typed status: a DNS/connect/socket cause becomes
            // UpstreamUnreachable ("could not reach the booking site") instead
            // of being mislabelled as a vendor 5xx — the exact confusion the
            // incident turned on. A real 5xx has no transport cause, so it
            // still falls through to UpstreamUnavailable.
            val msg = e.message.orEmpty()
            if (msg.contains("429") || msg.contains("rate")) {
                throw AvailabilityProviderError.RateLimited(e)
            }
            throw upstreamAvailabilityError(cause = e, httpStatus = null)
        }
}

internal fun mapRecgovUpstreamError(e: Throwable): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val msg = e.message.orEmpty()
    return when {
        e is AvailabilityProviderError.RateLimited || msg == "rate_limited" ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("rate_limited")
        msg.contains("429") ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("rate_limited")
        else ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("upstream_5xx")
    }
}

private fun Campsite.recgovSiteId(): String =
    bookingProviderRef
        ?.takeIf { bookingProvider == BookingProvider.RECGOV.id }
        ?: dataProviderRef.serialize()

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

private fun observationsFromCampsites(
    merged: Map<String, Map<String, String>>,
    dates: List<LocalDate>,
    observedAtByDate: Map<LocalDate, Instant>,
    campsiteIdsBySiteId: Map<String, Long> = emptyMap(),
): List<CampsiteDayObservation> =
    merged.flatMap { (siteId, byDate) ->
        dates.map { date ->
            CampsiteDayObservation(
                campsiteId = campsiteIdsBySiteId[siteId],
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
        status.equals("Reserved", true) -> AvailabilityStatus.RESERVED
        else -> AvailabilityStatus.RESERVED
    }
}

private fun inferReopenDate(
    merged: Map<String, Map<String, String>>,
    today: LocalDate,
): AvailabilitySeasonBlock? {
    val candidates =
        merged.values
            .flatMap { it.entries }
            .filter { (_, status) ->
                classifyRecgovStatus(status).isOnlineBookable
            }.mapNotNull { (date, _) ->
                runCatching { LocalDate.parse(date) }.getOrNull()
            }.filter { !it.isBefore(today) }
    val earliest = candidates.minOrNull() ?: return null
    return AvailabilitySeasonBlock(reopensOn = earliest.toString())
}

private fun directFetchCacheBlock(): AvailabilityCacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)
