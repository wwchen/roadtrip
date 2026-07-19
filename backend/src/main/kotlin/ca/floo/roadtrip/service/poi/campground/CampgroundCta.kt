package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.model.api.poi.PoiCtaSchema
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AspiraBookingDisplay
import ca.floo.roadtrip.service.availability.provider.AspiraBookingUrl
import ca.floo.roadtrip.service.availability.provider.RecGovBookingDisplay
import ca.floo.roadtrip.service.availability.provider.ReservationUrlTemplate
import ca.floo.roadtrip.service.availability.provider.ReserveAmericaBookingDisplay
import ca.floo.roadtrip.service.availability.provider.ReserveCaliforniaBookingDisplay
import ca.floo.roadtrip.service.availability.provider.ReserveCaliforniaBookingUrl
import ca.floo.roadtrip.service.etl.vendors.campflare.CampflareUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

// Backend-computed actions for a POI pin. The drawer reads {url, label, kind}
// verbatim — the FE doesn't own per-vendor precedence or URL construction.
private const val INFO_CTA_KIND = "info"
private const val RESERVE_CTA_KIND = "reserve"
private const val CAMPFLARE_CTA_LABEL = "View on Campflare"

internal class CampgroundCta(
    clock: Clock = Clock.systemUTC(),
) {
    private val providers: List<CampgroundCtaProvider> =
        listOf(
            RecGovCampgroundCtaProvider,
            AspiraCampgroundCtaProvider(clock),
            ReserveAmericaCampgroundCtaProvider,
            ReserveCaliforniaCampgroundCtaProvider,
        )

    // Display name for the booking system that reservations on this pin
    // flow through. Same per-vendor knowledge as computeCtas, surfaced as
    // a string for the drawer footer.
    fun bookingSystem(
        providerRefJson: String?,
        reserveUrl: String?,
        infoUrl: String?,
    ): String? {
        val providerRef = providerRefJson?.let { parseProviderRef(it) }
        val upstreamUrl = providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl)
        return providers.firstNotNullOfOrNull { it.bookingSystem(providerRef, upstreamUrl) }
    }

    fun computeCtas(
        providerRefJson: String?,
        ctaProviderRefJson: String?,
        reserveUrl: String?,
        infoUrl: String?,
    ): List<PoiCtaSchema> {
        val primaryProviderRef = (ctaProviderRefJson ?: providerRefJson)?.let { parseProviderRef(it) }
        val sourceProviderRef = providerRefJson?.let { parseProviderRef(it) }
        val primaryCta =
            primaryReserveCta(
                providerRef = primaryProviderRef,
                reserveUrl = reserveUrl,
                infoUrl = infoUrl,
            ) ?: infoUrl?.takeIf { it.isNotBlank() }?.let {
                PoiCtaSchema(
                    url = it,
                    label = ExternalInfoLinkLabels.forUrl(it),
                    kind = INFO_CTA_KIND,
                )
            }
        return listOfNotNull(
            primaryCta,
            campflareCta(sourceProviderRef),
        ).distinctBy { it.url }
    }

    // Parse booking provider ref JSON into typed ref. Mirrors the writer
    // in CampgroundRepo.bookingProviderRefJson() — presence of a field
    // is the discriminator, no explicit type tag.
    private fun parseProviderRef(json: String): BookingProviderRef? {
        val obj =
            runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                ?: return null

        obj["recgov_id"]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.RecGov(facilityId = it)
        }

        obj["campflare_id"]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.Campflare(campgroundId = it)
        }

        // Aspira: writer uses Long for both ids; reading as Long avoids the
        // 32-bit truncation that the legacy `Int` parser introduced.
        val mapId = obj["mapId"]?.jsonPrimitive?.longOrNull
        val transactionLocationId = obj["transactionLocationId"]?.jsonPrimitive?.longOrNull
        if (mapId != null && transactionLocationId != null) {
            val resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.longOrNull
            return BookingProviderRef.Aspira(
                tenant = null,
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = resourceLocationId,
            )
        }

        obj["park_id"]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.ReserveAmerica(
                contractCode = obj["contract_code"]?.jsonPrimitive?.contentOrNull,
                parkId = it,
            )
        }

        obj["facility_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.toLongOrNull() != null }?.let {
            return BookingProviderRef.ReserveAmerica(contractCode = null, parkId = it)
        }

        val placeId = obj["place_id"]?.jsonPrimitive?.longOrNull
        val facilityIds =
            runCatching {
                obj["facility_ids"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.longOrNull }
                    .orEmpty()
            }.getOrDefault(emptyList())
        if (placeId != null && facilityIds.isNotEmpty()) {
            return BookingProviderRef.ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
        }

        return null
    }

    private fun campflareCta(providerRef: BookingProviderRef?): PoiCtaSchema? {
        val campflare = providerRef as? BookingProviderRef.Campflare ?: return null
        return PoiCtaSchema(
            url = CampflareUrls.campground(campflare.campgroundId),
            label = CAMPFLARE_CTA_LABEL,
            kind = INFO_CTA_KIND,
        )
    }

    private fun primaryReserveCta(
        providerRef: BookingProviderRef?,
        reserveUrl: String?,
        infoUrl: String?,
    ): PoiCtaSchema? =
        providers.firstNotNullOfOrNull {
            it.reserveCta(providerRef, providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl))
        }

    private fun providerUrl(
        reserveUrl: String?,
        infoUrl: String?,
    ): String? =
        reserveUrl
            ?.takeIf { it.isNotBlank() }
            ?: infoUrl?.takeIf { it.isNotBlank() }

    companion object {
        // Convenience for the route layer — uses system clock.
        val default: CampgroundCta = CampgroundCta()
    }
}

private interface CampgroundCtaProvider {
    fun bookingSystem(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): String? = null

    fun reserveCta(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? = null
}

private object RecGovCampgroundCtaProvider : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? BookingProviderRef.RecGov)?.let { RecGovBookingDisplay.BOOKING_SYSTEM_LABEL }

    override fun reserveCta(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        providerRef as? BookingProviderRef.RecGov ?: return null
        val url = infoUrl?.takeIf { it.isNotBlank() } ?: return null
        return reserveCta(
            url = url,
            label = RecGovBookingDisplay.CAMPGROUND_CTA_LABEL,
        )
    }
}

private class AspiraCampgroundCtaProvider(
    private val clock: Clock,
) : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): String? {
        providerRef as? BookingProviderRef.Aspira ?: return null
        return AspiraBookingDisplay.bookingSystemLabel(infoUrl?.let(UrlHosts::extract))
    }

    override fun reserveCta(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val aspira = providerRef as? BookingProviderRef.Aspira ?: return null
        val host = infoUrl?.let(UrlHosts::extract) ?: return null
        return reserveCta(
            url = deeplink(host, aspira),
            label = AspiraBookingDisplay.ctaLabel(host),
        )
    }

    private fun deeplink(
        host: String,
        ref: BookingProviderRef.Aspira,
    ): String {
        val today = LocalDate.now(clock.withZone(aspiraAnchorTimeZone))
        val template = AspiraBookingUrl.template(host, ref.transactionLocationId, ref.mapId, ref.resourceLocationId)
        return ReservationUrlTemplate.fill(template, today, today.plusDays(1))
    }

    private companion object {
        // TODO: per-tenant TZ via YAML once we ingest more parks across more zones.
        // For now, every Aspira tenant we run lives close enough to Eastern that
        // an EST anchor produces a usable today/tomorrow booking page.
        val aspiraAnchorTimeZone: ZoneId = ZoneId.of("America/New_York")
    }
}

private object ReserveAmericaCampgroundCtaProvider : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? BookingProviderRef.ReserveAmerica)?.let { ReserveAmericaBookingDisplay.BOOKING_SYSTEM_LABEL }
}

private object ReserveCaliforniaCampgroundCtaProvider : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? BookingProviderRef.ReserveCalifornia)?.let { ReserveCaliforniaBookingDisplay.BOOKING_SYSTEM_LABEL }

    override fun reserveCta(
        providerRef: BookingProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val reserveCalifornia = providerRef as? BookingProviderRef.ReserveCalifornia ?: return null
        return reserveCta(
            url = ReserveCaliforniaBookingUrl.park(reserveCalifornia.placeId),
            label = ReserveCaliforniaBookingDisplay.PARK_CTA_LABEL,
        )
    }
}

private fun reserveCta(
    url: String,
    label: String,
): PoiCtaSchema =
    PoiCtaSchema(
        url = url,
        label = label,
        kind = RESERVE_CTA_KIND,
    )

private fun parseProviderRef(json: String): BookingProviderRef? {
    val obj =
        runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return null

    obj["recgov_id"]?.jsonPrimitive?.contentOrNull?.let {
        return BookingProviderRef.RecGov(facilityId = it)
    }

    obj["campflare_id"]?.jsonPrimitive?.contentOrNull?.let {
        return BookingProviderRef.Campflare(campgroundId = it)
    }

    val mapId = obj["mapId"]?.jsonPrimitive?.longOrNull
    val transactionLocationId = obj["transactionLocationId"]?.jsonPrimitive?.longOrNull
    if (mapId != null && transactionLocationId != null) {
        return BookingProviderRef.Aspira(
            tenant = null,
            transactionLocationId = transactionLocationId,
            mapId = mapId,
            resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.longOrNull,
        )
    }

    obj["park_id"]?.jsonPrimitive?.contentOrNull?.let {
        return BookingProviderRef.ReserveAmerica(
            contractCode = obj["contract_code"]?.jsonPrimitive?.contentOrNull,
            parkId = it,
        )
    }

    obj["facility_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.toLongOrNull() != null }?.let {
        return BookingProviderRef.ReserveAmerica(contractCode = null, parkId = it)
    }

    val placeId = obj["place_id"]?.jsonPrimitive?.longOrNull
    val facilityIds =
        runCatching {
            obj["facility_ids"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.longOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())
    if (placeId != null && facilityIds.isNotEmpty()) {
        return BookingProviderRef.ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
    }

    return null
}
