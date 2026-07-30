package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `/api/health/ready` body. Served with `200` when [status] is
 * [State.READY] and `503` otherwise, so a probe that reads only the status line
 * behaves correctly; the per-dependency fields are for a human reading the
 * body during an incident.
 */
@Serializable
internal data class ReadinessResponseDto(
    val status: State,
    val now: Long,
    val database: Dependency,
) {
    @Serializable
    enum class State {
        @SerialName("ready")
        READY,

        @SerialName("not_ready")
        NOT_READY,
    }

    @Serializable
    enum class Dependency {
        @SerialName("up")
        UP,

        @SerialName("down")
        DOWN,
    }
}
