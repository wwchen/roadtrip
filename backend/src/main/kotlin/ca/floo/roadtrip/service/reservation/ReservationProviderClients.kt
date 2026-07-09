package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient

class ReservationProviderClients(
    val recgovClient: RecGovAvailabilityClient,
    val aspiraClient: AspiraAvailabilityClient,
    val reserveAmericaClient: ReserveAmericaAvailabilityClient,
    val reserveCaliforniaClient: ReserveCaliforniaAvailabilityClient,
    val campflareClient: CampflareAvailabilityClient,
) : AutoCloseable {
    override fun close() {
        val failures = mutableListOf<Throwable>()
        for (client in listOf(campflareClient, reserveCaliforniaClient, reserveAmericaClient, aspiraClient, recgovClient)) {
            runCatching { client.close() }.onFailure { failures += it }
        }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}
