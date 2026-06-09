package ca.floo.roadtrip.models

// Validation outcome for a single DTO row. Errors are counted in
// poller_runs.counts and the row is dropped — bad upstream data doesn't
// silently poison `pois` (RFC decision #20).
sealed class ValidationResult<out DTO> {
    data class Ok<DTO>(
        val dto: DTO,
    ) : ValidationResult<DTO>()

    data class Bad<DTO>(
        val sourceId: String?, // best-effort; null if we can't even extract the id
        val errors: List<String>,
    ) : ValidationResult<DTO>()
}
