package ca.floo.roadtrip.apigen

/** One property of a generated interface. */
internal data class TsField(
    val name: String,
    val type: String,
    val optional: Boolean,
)

/** One `export interface`. Fields stay in Kotlin declaration order. */
internal data class TsInterface(
    val name: String,
    val fields: List<TsField>,
)

/** One `export type X = 'a' | 'b'`, from an enum's serial names. */
internal data class TsEnum(
    val name: String,
    val values: List<String>,
)

/** One row of the emitted `API_ENDPOINTS` literal. */
internal data class TsEndpoint(
    val method: String,
    val path: String,
    val request: String?,
    val response: String?,
)

/**
 * Anything the contract cannot express in TypeScript. Every message names the
 * class, because the reader is someone who just added a DTO and needs to know
 * which one the build refused.
 */
internal class ApiTypeGenerationException(
    message: String,
) : IllegalStateException(message)
