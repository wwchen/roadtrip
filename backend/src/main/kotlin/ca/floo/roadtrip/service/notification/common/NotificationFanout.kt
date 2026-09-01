package ca.floo.roadtrip.service.notification.common

import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.time.LocalDate

class NotificationFanout(
    private val services: List<NotificationService>,
) : NotificationSender,
    Closeable {
    override suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        targets: List<NotificationTarget>,
    ): Boolean =
        sendToTargets(targets) { service, target ->
            service.sendWatchStatus(notice, target)
        }

    override suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        targets: List<NotificationTarget>,
        appRootUrl: String?,
    ): Boolean {
        if (openings.isEmpty()) return false
        return sendToTargets(targets) { service, target ->
            service.sendWatchOpenings(
                watchId = watchId,
                startDate = startDate,
                endDate = endDate,
                openings = openings,
                target = target,
                appRootUrl = appRootUrl,
            )
        }
    }

    override suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        error: String?,
        detail: String?,
        targets: List<NotificationTarget>,
    ): Boolean =
        sendToTargets(targets) { service, target ->
            service.sendAtcResult(
                watchId = watchId,
                vendor = vendor,
                status = status,
                request = request,
                response = response,
                error = error,
                detail = detail,
                target = target,
            )
        }

    override fun close() {
        services.filterIsInstance<Closeable>().forEach { it.close() }
    }

    private suspend fun sendToTargets(
        targets: List<NotificationTarget>,
        send: suspend (NotificationService, NotificationTarget) -> Boolean,
    ): Boolean {
        if (targets.isEmpty()) return false
        return targets
            .map { target ->
                val service = services.firstOrNull { it.canHandle(target) } ?: return@map false
                send(service, target)
            }.all { it }
    }
}
