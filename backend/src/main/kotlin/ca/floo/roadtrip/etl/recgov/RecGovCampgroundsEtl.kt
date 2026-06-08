package ca.floo.roadtrip.etl.recgov

import ca.floo.roadtrip.etl.Address
import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.ProviderRef
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// RIDB facilities feed → Poi.Campground.
//
// Capture path: data/raw/<slug>/<ts>/page-NNN.json. Each page is the
// envelope-wrapped RIDB /organizations/<orgId>/facilities response (a
// RECDATA array of Facility records). Multi-part read concatenates
// every page into one logical capture.
//
// One ETL class, multiple registry entries (one per agency). The
// agency identity isn't on the row — every facility in a given
// data_source belongs to that source's agency. provider_ref is RecGov
// with the FacilityID as the recgov_id, so the booker (which keys
// alerts off recgov_id) FKs cleanly to whatever the user picks on the
// map.
class RecGovCampgroundsEtl(
    override val sourceName: String,
) : SourceEtl<RecGovDto, Poi.Campground> {
    override val multiPart: Boolean = true

    override fun parseMulti(envelopes: List<Envelope>): RecGovDto {
        require(envelopes.isNotEmpty()) { "$sourceName: no pages" }
        val rows = mutableListOf<Facility>()
        for (env in envelopes) {
            val page = json.decodeFromJsonElement(RidbPageDto.serializer(), env.payload)
            rows += page.RECDATA
        }
        return RecGovDto(rows = rows, fetchedAt = parseFetchedAt(envelopes.first()))
    }

    override fun validate(dto: RecGovDto): ValidationResult<RecGovDto> {
        val errors = mutableListOf<String>()
        if (dto.rows.isEmpty()) errors += "no rows in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: RecGovDto,
        ctx: TransformCtx,
    ): List<Poi.Campground> {
        val bucket = ctx.legendBucketFor(sourceName)
        return dto.rows.mapNotNull { transformRow(it, dto.fetchedAt, bucket) }
    }

    private fun transformRow(
        row: Facility,
        fetchedAt: Instant,
        bucket: String?,
    ): Poi.Campground? {
        val name = row.FacilityName?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.FacilityLatitude
        val lon = row.FacilityLongitude
        // Some RIDB rows ship 0,0 for missing geometry; reject those —
        // a dot in the Atlantic is worse than no dot.
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) return null

        val firstAddr = row.FACILITYADDRESS?.firstOrNull()
        val address =
            firstAddr?.let {
                Address(
                    street = it.FacilityStreetAddress1?.takeIf { s -> s.isNotBlank() },
                    city = it.City?.takeIf { s -> s.isNotBlank() },
                    state = it.AddressStateCode?.takeIf { s -> s.isNotBlank() },
                    postcode = it.PostalCode?.takeIf { s -> s.isNotBlank() },
                    country = it.AddressCountryCode?.takeIf { s -> s.isNotBlank() } ?: "US",
                )
            }

        return Poi.Campground(
            source = sourceName,
            sourceId = "recgov-${row.FacilityID}",
            name = name,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            region = address?.state ?: firstAddr?.AddressStateCode,
            country = address?.country ?: "US",
            phone = row.FacilityPhone?.takeIf { it.isNotBlank() },
            address = address,
            // FacilityReservationURL points at the recgov reservation page
            // when bookable; otherwise the facility detail page is enough.
            infoUrl =
                row.FacilityReservationURL?.takeIf { it.isNotBlank() }
                    ?: "https://www.recreation.gov/camping/campgrounds/${row.FacilityID}",
            fetchedAt = fetchedAt,
            lastVerified = null,
            providerRef = ProviderRef.RecGov(recgovId = row.FacilityID.toString()),
            amenities = emptyList(),
            activities = emptyList(),
            sites = null,
            season = null,
            near = null,
            photoUrl = null,
            cellCoverage = null,
            ratingReviews = null,
            legendBucket = bucket,
        )
    }

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

// RIDB page envelope: { METADATA: {...}, RECDATA: [Facility, ...] }.
// Field names match RIDB's PascalCase verbatim — kotlinx-serialization
// would normally complain, but the fields aren't generic enough to
// alias, so we suppress and live with the naming noise.
@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class RidbPageDto(
    val RECDATA: List<Facility> = emptyList(),
)

@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class Facility(
    val FacilityID: Long,
    val FacilityName: String? = null,
    val FacilityLatitude: Double? = null,
    val FacilityLongitude: Double? = null,
    val FacilityPhone: String? = null,
    val FacilityReservationURL: String? = null,
    val FACILITYADDRESS: List<FacilityAddress>? = null,
)

@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class FacilityAddress(
    val FacilityStreetAddress1: String? = null,
    val City: String? = null,
    val AddressStateCode: String? = null,
    val PostalCode: String? = null,
    val AddressCountryCode: String? = null,
)

data class RecGovDto(
    val rows: List<Facility>,
    val fetchedAt: Instant,
)
