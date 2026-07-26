package ca.floo.roadtrip.service.auth

/**
 * Slugs written to `user_identity.upstream_provider`.
 *
 * Deliberately plain strings rather than an enum: this column records what an
 * external provider reported, and an unrecognized value must round-trip
 * unchanged rather than fail to parse. A dialect maps the vendor's spelling onto
 * these where it recognizes one, and passes anything else through.
 */
internal object UpstreamProviders {
    const val GOOGLE = "google"
    const val APPLE = "apple"
    const val PASSWORD = "password"
}
