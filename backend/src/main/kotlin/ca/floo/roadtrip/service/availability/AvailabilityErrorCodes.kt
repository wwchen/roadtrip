package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityProviderError

/** Wire error code for a provider failure. Shared by the detail and bulk reads. */
fun availabilityErrorCode(e: AvailabilityProviderError): String =
    when (e) {
        is AvailabilityProviderError.RateLimited -> "rate_limited"
        is AvailabilityProviderError.UpstreamBlocked -> "upstream_blocked"
        is AvailabilityProviderError.UpstreamUnavailable -> "upstream_5xx"
        is AvailabilityProviderError.UpstreamUnreachable -> "upstream_unreachable"
        is AvailabilityProviderError.Misconfigured -> "provider_misconfigured"
        is AvailabilityProviderError.Unsupported -> "unsupported"
        is AvailabilityProviderError.WrongRefType -> "provider_misconfigured"
    }
