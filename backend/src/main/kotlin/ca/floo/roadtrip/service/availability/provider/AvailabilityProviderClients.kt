package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient

class AvailabilityProviderClients(
    val recgovClient: RecGovAvailabilityClient,
    val aspiraClient: AspiraAvailabilityClient,
    val reserveAmericaClient: ReserveAmericaAvailabilityClient,
    val reserveCaliforniaClient: ReserveCaliforniaAvailabilityClient,
    val campflareClient: CampflareAvailabilityClient,
) : AutoCloseable {
    override fun close() {
        val failures = mutableListOf<Throwable>()
        for (client in listOf<AutoCloseable>(
            campflareClient,
            reserveCaliforniaClient,
            reserveAmericaClient,
            aspiraClient,
            recgovClient,
        )) {
            runCatching { client.close() }.onFailure { failures += it }
        }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}
