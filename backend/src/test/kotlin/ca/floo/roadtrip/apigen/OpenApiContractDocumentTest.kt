package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiBody
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.api.CheckNowCooldownDto
import ca.floo.roadtrip.model.api.HTTP_CONFLICT
import ca.floo.roadtrip.model.api.HTTP_OK
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WATCHES = "/api/watches"
private const val WATCH_DELETE = "/api/watches/{id}/delete"
private const val FORCE_POLLER = "/api/availability/pollers/{id}/force"
private const val SCHEMA_PREFIX = "#/components/schemas/"

/**
 * The contract pass over a document, against hand-built `PathItem`s.
 *
 * Every test that boots `/api/docs` mounts a *slice* of the routing tree, so the
 * rows whose controllers are expensive to assemble — the watch surface, the
 * poller force — are asserted here instead, and `OpenApiSmokeTest` keeps the
 * whole-document invariants its own slice supports.
 */
class OpenApiContractDocumentTest {
    @Test
    fun `a POST row carries its 201, its request body and the 401 its access level implies`() {
        val doc = applied(WATCHES to PathItem(post = Operation(summary = "Create a watch")))
        val post = operation(doc, WATCHES, "post")
        assertEquals("Create a watch", post["summary"]!!.jsonPrimitive.content, "the tree's summary survives")
        assertEquals(
            "${SCHEMA_PREFIX}AvailabilityWatchCreateRequest",
            ref(post["requestBody"]!!.jsonObject),
        )
        assertEquals(
            true,
            post["requestBody"]!!
                .jsonObject["required"]!!
                .jsonPrimitive.content
                .toBoolean(),
        )
        val responses = post["responses"]!!.jsonObject
        assertEquals("${SCHEMA_PREFIX}AvailabilityWatchResponse", ref(responses.getValue("201").jsonObject))
        assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", ref(responses.getValue("401").jsonObject))
        assertTrue("200" !in responses.keys, "the success status is 201, so no 200 is published: ${responses.keys}")
    }

    @Test
    fun `a 429 row references the cooldown DTO, not the generic error`() {
        val doc = applied(FORCE_POLLER to PathItem(post = Operation()))
        val responses = operation(doc, FORCE_POLLER, "post")["responses"]!!.jsonObject
        assertEquals("${SCHEMA_PREFIX}CheckNowCooldownDto", ref(responses.getValue("429").jsonObject))
        assertEquals("${SCHEMA_PREFIX}CheckNowResponseDto", ref(responses.getValue("200").jsonObject))
        assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", ref(responses.getValue("404").jsonObject))
    }

    @Test
    fun `a 204 is published with no content`() {
        val doc = applied(WATCH_DELETE to PathItem(post = Operation()))
        val noContent = operation(doc, WATCH_DELETE, "post")["responses"]!!.jsonObject.getValue("204").jsonObject
        assertNull(noContent["content"], "a 204 carries no body, so it publishes no content")
        assertEquals("No Content", noContent["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an anonymous row gets no 401 and no 403`() {
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/health" to PathItem(get = Operation())),
            ) { _, _ -> RouteAccess.Anonymous }
        val responses = operation(doc, "/api/health", "get")["responses"]!!.jsonObject
        assertEquals(setOf("200", "400"), responses.keys)
    }

    @Test
    fun `a role-gated row gets both the 401 and the 403`() {
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/health" to PathItem(get = Operation())),
            ) { _, _ -> RouteAccess.HasRole(Role.ADMIN) }
        assertEquals(
            setOf("200", "400", "401", "403"),
            operation(doc, "/api/health", "get")["responses"]!!.jsonObject.keys,
        )
    }

    @Test
    fun `a row whose access level already 401s is not published twice`() {
        val doc = applied("/api/watches/{id}" to PathItem(get = Operation()))
        val unauthorized = operation(doc, "/api/watches/{id}", "get")["responses"]!!.jsonObject.getValue("401")
        assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", ref(unauthorized.jsonObject))
        assertNull(schemaOf(unauthorized.jsonObject)["oneOf"], "one class at 401 is a bare ref, not a oneOf")
    }

    @Test
    fun `two classes at one status become a oneOf`() {
        val row =
            ApiEndpoint(
                ApiMethod.GET,
                "/api/two-bodies",
                success = ApiBody(HTTP_OK, BuildInfoDto::class),
                errors =
                    listOf(
                        ApiBody(HTTP_CONFLICT, ApiErrorSchema::class),
                        ApiBody(HTTP_CONFLICT, CheckNowCooldownDto::class),
                    ),
            )
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/two-bodies" to PathItem(get = Operation())),
                listOf(row),
            ) { _, _ -> RouteAccess.Anonymous }
        val conflict = operation(doc, "/api/two-bodies", "get")["responses"]!!.jsonObject.getValue("409")
        assertEquals(
            listOf("${SCHEMA_PREFIX}ApiErrorSchema", "${SCHEMA_PREFIX}CheckNowCooldownDto"),
            schemaOf(conflict.jsonObject)["oneOf"]!!.jsonArray.map {
                it.jsonObject
                    .getValue("\$ref")
                    .jsonPrimitive.content
            },
        )
    }

    @Test
    fun `an unmounted row is skipped, because the boot guard is what requires it`() {
        val doc = applied(WATCHES to PathItem(post = Operation()))
        assertEquals(setOf(WATCHES), doc.paths.keys, "no path is invented for the rows this document does not mount")
    }

    @Test
    fun `a path the contract does not name is left exactly as the tree described it`() {
        val doc = applied("/test/echo" to PathItem(get = Operation(summary = "echo")))
        val untouched = operation(doc, "/test/echo", "get")
        assertEquals("echo", untouched["summary"]!!.jsonPrimitive.content)
        assertNull(untouched["responses"], "an uncontracted operation gets no responses")
        assertNull(untouched["requestBody"])
    }

    @Test
    fun `every schema the contract declares is in components, and every ref resolves`() {
        val doc = applied(WATCHES to PathItem(post = Operation()))
        val encoded = Json.parseToJsonElement(encodeApiJson(doc)).jsonObject
        val declared = encoded["components"]!!.jsonObject["schemas"]!!.jsonObject.keys
        assertEquals(contractSchemas().keys, declared)
        val unresolved =
            refsIn(encoded)
                .map { it.removePrefix(SCHEMA_PREFIX) }
                .filterNot { it in declared }
                .distinct()
                .sorted()
        assertEquals(emptyList(), unresolved)
    }

    private fun applied(vararg items: Pair<String, PathItem>): OpenApiDoc =
        OpenApiContractDocument.apply(docWith(*items)) { _, path ->
            // Mirrors the levels the real tree declares for the rows under test.
            when {
                path.startsWith("$WATCHES/") -> RouteAccess.UserOrCapability
                path.startsWith(WATCHES) -> RouteAccess.User
                else -> RouteAccess.Anonymous
            }
        }

    private fun docWith(vararg items: Pair<String, PathItem>): OpenApiDoc =
        OpenApiDoc(
            info = OpenApiInfo(title = "test", version = "0"),
            paths = items.associate { (path, item) -> path to ReferenceOr.Value(item) },
        )

    private fun operation(
        doc: OpenApiDoc,
        path: String,
        verb: String,
    ): JsonObject =
        Json
            .parseToJsonElement(encodeApiJson(doc))
            .jsonObject["paths"]!!
            .jsonObject
            .getValue(path)
            .jsonObject
            .getValue(verb)
            .jsonObject

    /** The one media type's schema, from a `requestBody` or a `response`. */
    private fun schemaOf(holder: JsonObject): JsonObject =
        holder["content"]!!
            .jsonObject
            .values
            .single()
            .jsonObject["schema"]!!
            .jsonObject

    private fun ref(holder: JsonObject): String = schemaOf(holder).getValue("\$ref").jsonPrimitive.content

    /** Every reference anywhere in the document, however deeply nested. */
    private fun refsIn(element: JsonElement): List<String> =
        when (element) {
            is JsonObject ->
                element.entries.flatMap { (key, value) ->
                    if (key == "\$ref") listOf(value.jsonPrimitive.content) else refsIn(value)
                }
            is JsonArray -> element.flatMap(::refsIn)
            else -> emptyList()
        }
}
