package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.domain.provider.BookingTenant

/** Index tenants by their registry code, dropping code-less rows (other providers' tenants). */
internal fun List<BookingTenant>.byCode(): Map<String, BookingTenant> = mapNotNull { it.code?.let { code -> code to it } }.toMap()
