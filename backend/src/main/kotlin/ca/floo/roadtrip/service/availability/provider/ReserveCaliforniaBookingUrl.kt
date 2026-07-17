package ca.floo.roadtrip.service.availability.provider

internal object ReserveCaliforniaBookingUrl {
    private const val PARK_URL = "https://reservecalifornia.com/park"

    fun park(placeId: Long): String = "$PARK_URL/$placeId"
}
