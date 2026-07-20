package ca.floo.roadtrip.model.metadata

sealed interface TransformResult<out OUT> {
    data class Ok<OUT>(
        val record: OUT,
    ) : TransformResult<OUT>

    data class Bad(
        val sourceId: String?,
        val errors: List<String>,
    ) : TransformResult<Nothing>
}
