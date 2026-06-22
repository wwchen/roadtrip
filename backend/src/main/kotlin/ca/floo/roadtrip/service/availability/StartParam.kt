package ca.floo.roadtrip.service.availability

import java.time.LocalDate

internal sealed class StartParam {
    data class Ok(
        val value: LocalDate,
    ) : StartParam()

    object Invalid : StartParam()
}
