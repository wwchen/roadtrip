package ca.floo.roadtrip.service.availability

import java.time.LocalDate

internal fun parseStartParam(
    raw: LocalDate?,
    today: LocalDate,
    horizonDays: Int,
): StartParam {
    if (raw == null) return StartParam.Ok(today)
    if (raw.isBefore(today)) return StartParam.Invalid
    if (raw.isAfter(today.plusDays(horizonDays.toLong()))) return StartParam.Invalid
    return StartParam.Ok(raw)
}
