package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.guardBodies
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
import kotlin.reflect.KClass

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

/** Built from the enum, so a verb added to [ApiMethod] is a compile error here rather than a 500 from `/api/docs`. */
private val operationSlots: Map<ApiMethod, OperationSlot> =
    ApiMethod.entries.associateWith { method ->
        when (method) {
            ApiMethod.GET -> OperationSlot({ it.get }, { item, op -> item.copy(get = op) })
            ApiMethod.POST -> OperationSlot({ it.post }, { item, op -> item.copy(post = op) })
            ApiMethod.PUT -> OperationSlot({ it.put }, { item, op -> item.copy(put = op) })
            ApiMethod.PATCH -> OperationSlot({ it.patch }, { item, op -> item.copy(patch = op) })
            ApiMethod.DELETE -> OperationSlot({ it.delete }, { item, op -> item.copy(delete = op) })
            ApiMethod.HEAD -> OperationSlot({ it.head }, { item, op -> item.copy(head = op) })
            ApiMethod.OPTIONS -> OperationSlot({ it.options }, { item, op -> item.copy(options = op) })
        }
    }

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
     * One walk per process for the real contract. Every request for the document
     * would otherwise re-walk the whole descriptor graph.
     */
    private val defaultRendering by lazy { renderingFor(ApiContract.endpoints) }

    fun apply(
        doc: OpenApiDoc,
        endpoints: List<ApiEndpoint> = ApiContract.endpoints,
        accessOf: (ApiMethod, String) -> RouteAccess?,
    ): OpenApiDoc {
        // Identity, not equality: the cached walk is for the real contract, and the
        // caller that should hit it passes the very instance the default supplies.
        val rendering = if (endpoints === ApiContract.endpoints) defaultRendering else renderingFor(endpoints)
        val paths = LinkedHashMap(doc.paths)
        endpoints.zip(rendering.rows).forEach { (row, names) ->
            // A row whose operation is not mounted is skipped: every test mounts a
            // slice of the tree, and the boot guard in registerKoinRoutes is the
            // authority on a non-conditional row with no route.
            val item = paths[row.path]?.valueOrNull() ?: return@forEach
            val slot = operationSlots.getValue(row.method)
            val operation = slot.read(item) ?: return@forEach
            val described = operation.withContract(names, accessOf(row.method, row.path), rendering.guardSchemas)
            paths[row.path] = ReferenceOr.Value(slot.write(item, described))
        }
        // The contract's declarations win, but anything already in components —
        // the securitySchemes Ktor's own scan contributes to the Swagger UI's
        // copy, or a schema its JsonSchemaInference produced — is kept.
        val existing = doc.components
        val schemas = existing?.schemas.orEmpty() + rendering.schemas
        return doc.copy(
            paths = paths,
            components = existing?.copy(schemas = schemas) ?: Components(schemas = schemas),
        )
    }

    private fun renderingFor(endpoints: List<ApiEndpoint>): Rendering =
        walkContract(endpoints).let {
            Rendering(rows = it.rows, schemas = schemasOf(it), guardSchemas = it.guardSchemas)
        }

    private fun Operation.withContract(
        names: TsEndpoint,
        access: RouteAccess?,
        guardSchemas: Map<KClass<*>, String>,
    ): Operation =
        copy(
            requestBody = names.request?.let(::requestBodyFor) ?: requestBody,
            responses = responsesFor(names, access, guardSchemas),
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
        guardSchemas: Map<KClass<*>, String>,
    ): Responses =
        Responses(
            responses =
                (listOf(names.success) + names.errors + accessResponses(access, guardSchemas))
                    .distinct()
                    .groupBy({ it.status }, { it.type })
                    .toSortedMap()
                    .mapValues { (status, schemas) -> ReferenceOr.Value(responseFor(status, schemas)) },
        )

    /**
     * What the routing tree knows and a row must not restate, as schema names.
     * [guardBodies] is the one enumeration of which level refuses with what — the
     * body check behind every route test reads the same list — and an unmounted or
     * unlabelled leaf reaches here as null and adds nothing.
     *
     * The names come from [guardSchemas], the walk's own claim for those classes,
     * so a guard response can never `$ref` a name the collision map did not
     * approve.
     */
    private fun accessResponses(
        access: RouteAccess?,
        guardSchemas: Map<KClass<*>, String>,
    ): List<TsBody> =
        access?.guardBodies().orEmpty().map { body ->
            // Every guard body names a class: the guard answers ApiErrorSchema or nothing at all.
            TsBody(body.status, guardSchemas.getValue(requireNotNull(body.body)))
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

    /** One walk's products, cached together so the names and the schemas can never be from different walks. */
    private class Rendering(
        val rows: List<TsEndpoint>,
        val schemas: Map<String, JsonSchema>,
        val guardSchemas: Map<KClass<*>, String>,
    )
}
