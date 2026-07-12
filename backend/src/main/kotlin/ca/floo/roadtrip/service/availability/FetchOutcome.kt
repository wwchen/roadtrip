package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError

/** Platform-level outcome of one group's upstream fetch. Derived from the
 *  typed [AvailabilityProviderError] the adapter throws; provider-agnostic. */
enum class FetchOutcome { OK, RATE_LIMITED, UPSTREAM_5XX, BLOCKED, OTHER }
