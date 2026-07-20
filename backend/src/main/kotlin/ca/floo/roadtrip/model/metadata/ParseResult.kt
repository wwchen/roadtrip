package ca.floo.roadtrip.model.metadata

sealed interface ParseResult<out DTO> {
    data class Ok<DTO>(
        val dto: DTO,
    ) : ParseResult<DTO>

    data class Bad(
        val sourceId: String?,
        val errors: List<String>,
    ) : ParseResult<Nothing>
}
