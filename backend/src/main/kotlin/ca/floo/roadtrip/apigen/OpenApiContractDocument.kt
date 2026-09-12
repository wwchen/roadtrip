package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.HTTP_FORBIDDEN
import ca.floo.roadtrip.model.api.HTTP_UNAUTHORIZED
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Components
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.MediaType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.RequestBody
import io.ktor.openapi.Response
import io.ktor.openapi.Responses

private const val REQUEST_BODY_DESCRIPTION = "The body this endpoint decodes, as the DTO the contract names."

/**
 * Where one verb's operation lives on a [PathItem]. Ktor names each verb as its
 * own nullable property, so the dispatch is a registry rather than two parallel
 * `when` blocks that could disagree about which property a verb reads and writes.
 */
private class OperationSlot(
    val read: (PathItem) -> Operation?,
    val write: (PathItem, Operation) -> PathItem,
)

private val operationSlots: Map<ApiMethod, OperationSlot> =
    mapOf(
        ApiMethod.GET to OperationSlot({ it.get }, { item, op -> item.copy(get = op) }),
        ApiMethod.POST to OperationSlot({ it.post }, { item, op -> item.copy(post = op) }),
        ApiMethod.PUT to OperationSlot({ it.put }, { item, op -> item.copy(put = op) }),
        ApiMethod.PATCH to OperationSlot({ it.patch }, { item, op -> item.copy(patch = op) }),
        ApiMethod.DELETE to OperationSlot({ it.delete }, { item, op -> item.copy(delete = op) }),
        ApiMethod.HEAD to OperationSlot({ it.head }, { item, op -> item.copy(head = op) }),
        ApiMethod.OPTIONS to OperationSlot({ it.options }, { item, op -> item.copy(options = op) }),
    )

/**
 * The contract's half of `/api/docs/openapi.json`.
 *
 * The routing tree contributes the paths, the tags and the summaries; this adds
 * `components/schemas` from one walk of the whole contract, each named
 * operation's `requestBody`, and every status it can answer — plus the 401 and
 * 403 the route's own [RouteAccess] implies, which no row restates.
 */
internal object OpenApiContractDocument {
    /**
     * The claimed name of [ApiErrorSchema], the body every access-derived 401 and
     * 403 carries. [tsName] is the same function the walk's name claim applies,
     * and the "every reference resolves" cases are what prove the two agree.
     */
    private val apiErrorSchemaName = tsName(requireNotNull(ApiErrorSchema::class.qualifiedName))

    /**
     * One walk per process for the real contract. Every request for the document
     * would otherwise re-walk the whole descriptor graph.
     */
    private val defaultRendering by lazy { renderingFor(ApiContract.endpoints) }

    fun apply(
        doc: OpenApiDoc,
        endpoints: List<ApiEndpoint> = ApiContract.endpoints,
        accessOf: (ApiMethod, String) -> RouteAccess?,
    ): OpenApiDoc {
        val rendering = if (endpoints == ApiContract.endpoints) defaultRendering else renderingFor(endpoints)
        val paths = LinkedHashMap(doc.paths)
        endpoints.zip(rendering.rows).forEach { (row, names) ->
            // A row whose operation is not mounted is skipped: every test mounts a
            // slice of the tree, and the boot guard in registerKoinRoutes is the
            // authority on a non-conditional row with no route.
            val item = paths[row.path]?.valueOrNull() ?: return@forEach
            val slot = operationSlots.getValue(row.method)
            val operation = slot.read(item) ?: return@forEach
            val described = operation.withContract(names, accessOf(row.method, row.path))
            paths[row.path] = ReferenceOr.Value(slot.write(item, described))
        }
        return doc.copy(
            paths = paths,
            components =
                doc.components?.copy(schemas = rendering.schemas) ?: Components(schemas = rendering.schemas),
        )
    }

    private fun renderingFor(endpoints: List<ApiEndpoint>): Rendering =
        walkContract(endpoints).let { Rendering(rows = it.rows, schemas = schemasOf(it)) }

    private fun Operation.withContract(
        names: TsEndpoint,
        access: RouteAccess?,
    ): Operation =
        copy(
            requestBody = names.request?.let(::requestBodyFor) ?: requestBody,
            responses = responsesFor(names, access),
        )

    private fun requestBodyFor(schemaName: String): ReferenceOr<RequestBody> =
        ReferenceOr.value(
            RequestBody(
                description = REQUEST_BODY_DESCRIPTION,
                content =
                    mapOf(
                        ContentType.Application.Json to MediaType(schema = ReferenceOr.schema(schemaName)),
                    ),
                required = true,
            ),
        )

    /**
     * Every status this endpoint can answer, in ascending order. A status the row
     * and the access level both name appears once: the entries are deduplicated
     * by (status, schema), so the tree's 401 never doubles a declared one.
     */
    private fun responsesFor(
        names: TsEndpoint,
        access: RouteAccess?,
    ): Responses =
        Responses(
            responses =
                (listOf(names.success) + names.errors + accessResponses(access))
                    .distinct()
                    .groupBy({ it.status }, { it.type })
                    .toSortedMap()
                    .mapValues { (status, schemas) -> ReferenceOr.Value(responseFor(status, schemas)) },
        )

    /**
     * What the routing tree knows and a row must not restate. `RouteAccess.User`
     * can only ever answer 401 — `check` returns `Forbidden` for a role alone —
     * so it publishes one status, and `HasRole` publishes both. `Anonymous`,
     * `Signed` and `UserOrCapability` refuse nobody at this layer, so they add
     * nothing and whatever their handlers answer stays on the row.
     */
    private fun accessResponses(access: RouteAccess?): List<TsBody> =
        when (access) {
            RouteAccess.User -> listOf(TsBody(HTTP_UNAUTHORIZED, apiErrorSchemaName))
            is RouteAccess.HasRole ->
                listOf(
                    TsBody(HTTP_UNAUTHORIZED, apiErrorSchemaName),
                    TsBody(HTTP_FORBIDDEN, apiErrorSchemaName),
                )
            else -> emptyList()
        }

    private fun responseFor(
        status: Int,
        schemas: List<String?>,
    ): Response {
        val description = HttpStatusCode.fromValue(status).description
        val refs = schemas.filterNotNull().distinct().map { ReferenceOr.schema(it) }
        if (refs.isEmpty()) return Response(description = description)
        val schema: ReferenceOr<JsonSchema> = refs.singleOrNull() ?: ReferenceOr.value(JsonSchema(oneOf = refs))
        return Response(
            description = description,
            content = mapOf(ContentType.Application.Json to MediaType(schema = schema)),
        )
    }

    /** One walk's two products, cached together so the names and the schemas can never be from different walks. */
    private class Rendering(
        val rows: List<TsEndpoint>,
        val schemas: Map<String, JsonSchema>,
    )
}
