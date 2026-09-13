package ca.floo.roadtrip.apigen

/** One property of a generated interface. */
internal data class TsField(
    val name: String,
    val type: WireType,
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

/** One declared body in an emitted `API_ENDPOINTS` row: the status, and the type it carries. */
internal data class TsBody(
    val status: Int,
    val type: String?,
)

/**
 * One row of the emitted `API_ENDPOINTS` literal. [errors] carries the non-2xx
 * bodies the route can serialize, sorted by status then type so the row does not
 * move when the contract list is reordered.
 *
 * [requestRequired] is the row's `ApiEndpoint.requestRequired`, which the OpenAPI
 * document publishes as `requestBody.required`. It is carried here too so the one
 * walk stays the single source both renderers read.
 */
internal data class TsEndpoint(
    val method: String,
    val path: String,
    val request: String?,
    val requestRequired: Boolean,
    val success: TsBody,
    val errors: List<TsBody>,
)

/**
 * Anything the contract cannot express in TypeScript. Every message names the
 * class, because the reader is someone who just added a DTO and needs to know
 * which one the build refused.
 */
internal class ApiTypeGenerationException(
    message: String,
) : IllegalStateException(message)
