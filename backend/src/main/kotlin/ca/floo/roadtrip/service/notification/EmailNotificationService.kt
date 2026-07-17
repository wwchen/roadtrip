package ca.floo.roadtrip.service.notification

import java.time.LocalDate

interface EmailNotificationService {
    suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        appRootUrl: String? = null,
    ): Boolean
}
