package ca.floo.roadtrip.models

/**
 * Composite identity for a reservable on the wire. Format:
 *
 *   {type}:{vendor}:{vendor_id}
 *
 * Examples:
 *   site:recgov:330257
 *   site:aspira_bc:-2147483190
 *   site:aspira_pc:-2147483641
 *
 * Type is a [ReservableType] (closed enum). Vendor is a string here at the
 * model layer — at the service boundary, callers map it to a
 * `BookingProviderId` for adapter dispatch. Keeping vendor as a string at
 * the model layer avoids a circular dependency on `service/booking/` and
 * lets unknown-vendor strings round-trip without rejection.
 *
 * Vendor IDs are opaque to this parser. They may contain colons (split on
 * the *first two* colons only), URL-safe characters, leading minus signs
 * (Aspira). Empty vendor IDs are rejected.
 *
 * RFC 0008 §"Composite ID format".
 */
data class ReservableId(
    val type: ReservableType,
    val vendor: String,
    val vendorId: String,
) {
    init {
        require(vendor.isNotEmpty()) { "vendor must not be empty" }
        require(vendorId.isNotEmpty()) { "vendorId must not be empty" }
        require(!vendor.contains(':')) { "vendor must not contain ':' (got: $vendor)" }
        // vendorId is allowed to contain ':' so future ticket IDs like
        // "arches-2026-08-01-09:00" round-trip cleanly.
    }

    /** Wire form. Always lowercase type + vendor; vendor_id verbatim. */
    fun encode(): String = "${type.encode()}:${vendor.lowercase()}:$vendorId"

    override fun toString(): String = encode()

    companion object {
        /**
         * Parse a wire-form composite. Returns null on:
         *   - missing colon delimiters,
         *   - unknown type,
         *   - empty vendor or vendor_id,
         *   - vendor containing additional colons (would be ambiguous).
         *
         * Splits on the *first two* colons so the vendor_id can contain ':'.
         */
        fun parse(raw: String): ReservableId? {
            val firstColon = raw.indexOf(':')
            if (firstColon <= 0) return null
            val secondColon = raw.indexOf(':', firstColon + 1)
            if (secondColon <= firstColon + 1) return null
            val typeStr = raw.substring(0, firstColon)
            val vendor = raw.substring(firstColon + 1, secondColon)
            val vendorId = raw.substring(secondColon + 1)
            val type = ReservableType.parse(typeStr) ?: return null
            if (vendor.isEmpty() || vendorId.isEmpty()) return null
            return ReservableId(type = type, vendor = vendor.lowercase(), vendorId = vendorId)
        }
    }
}
