package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.fixtures.refsIn
import ca.floo.roadtrip.fixtures.schemaOf
import ca.floo.roadtrip.fixtures.schemaRef
import ca.floo.roadtrip.model.api.ApiBody
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.api.CheckNowCooldownDto
import ca.floo.roadtrip.model.api.HTTP_CONFLICT
import ca.floo.roadtrip.model.api.HTTP_FORBIDDEN
import ca.floo.roadtrip.model.api.HTTP_OK
import ca.floo.roadtrip.model.api.HTTP_UNAUTHORIZED
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
private const val API_ERROR_REF = "${SCHEMA_PREFIX}ApiErrorSchema"

/** The path of every synthetic row here; only one is ever in a document at a time. */
private const val SYNTHETIC = "/api/synthetic"

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
        val requestBody = post.getValue("requestBody").jsonObject
        assertEquals("${SCHEMA_PREFIX}AvailabilityWatchCreateRequest", schemaRef(requestBody))
        assertTrue(requestBody.getValue("required").jsonPrimitive.boolean, "a declared body is required")
        val responses = responses(doc, WATCHES, "post")
        assertEquals("${SCHEMA_PREFIX}AvailabilityWatchResponse", schemaRef(responses.getValue("201")))
        assertEquals(API_ERROR_REF, schemaRef(responses.getValue("401")))
        assertTrue("200" !in responses.keys, "the success status is 201, so no 200 is published: ${responses.keys}")
    }

    @Test
    fun `a 429 row references the cooldown DTO, not the generic error`() {
        val doc = applied(FORCE_POLLER to PathItem(post = Operation()))
        val responses = responses(doc, FORCE_POLLER, "post")
        assertEquals("${SCHEMA_PREFIX}CheckNowCooldownDto", schemaRef(responses.getValue("429")))
        assertEquals("${SCHEMA_PREFIX}CheckNowResponseDto", schemaRef(responses.getValue("200")))
        assertEquals(API_ERROR_REF, schemaRef(responses.getValue("404")))
    }

    @Test
    fun `a 204 is published with no content`() {
        val doc = applied(WATCH_DELETE to PathItem(post = Operation()))
        val noContent = responses(doc, WATCH_DELETE, "post").getValue("204").jsonObject
        assertNull(noContent["content"], "a 204 carries no body, so it publishes no content")
        assertEquals("No Content", noContent["description"]!!.jsonPrimitive.content)
    }

    /**
     * Resolution 21 at the one reference no row supplies: a guard response names
     * a declaration the walk claimed, so a `@SerialName` on the guard's DTO moves
     * the schema and the reference together instead of leaving a dangling `$ref`.
     */
    @Test
    fun `a guard body references a name the walk claimed`() {
        val guards = walkContract().guardSchemas
        assertTrue(guards.isNotEmpty(), "an access guard answers with at least one class")
        assertEquals(emptyList(), guards.values.filterNot { it in contractSchemas().keys })
    }

    @Test
    fun `an anonymous row gets no 401 and no 403`() {
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/health" to PathItem(get = Operation())),
            ) { _, _ -> RouteAccess.Anonymous }
        assertEquals(setOf("200", "400"), responses(doc, "/api/health", "get").keys)
    }

    @Test
    fun `a role-gated row gets both the 401 and the 403`() {
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/health" to PathItem(get = Operation())),
            ) { _, _ -> RouteAccess.HasRole(Role.ADMIN) }
        assertEquals(setOf("200", "400", "401", "403"), responses(doc, "/api/health", "get").keys)
    }

    @Test
    fun `a row that declares its own 401 publishes one bare ref`() {
        val doc = applied("/api/watches/{id}" to PathItem(get = Operation()))
        val unauthorized = responses(doc, "/api/watches/{id}", "get").getValue("401")
        assertEquals(API_ERROR_REF, schemaRef(unauthorized))
        assertNull(schemaOf(unauthorized)["oneOf"], "one class at 401 is a bare ref, not a oneOf")
    }

    /**
     * The dedup R6 turns on. `POST /api/booking/add-to-cart` is the live instance:
     * `RouteAccess.User` supplies a 401 and the row declares one too, and the two
     * must collapse into a single bare reference rather than a `oneOf` of one
     * class with itself.
     */
    @Test
    fun `the access-derived 401 merges with the one the row declares`() {
        val doc = withAccess(RouteAccess.User, ApiBody(HTTP_UNAUTHORIZED, ApiErrorSchema::class))
        val responses = responses(doc, SYNTHETIC, "get")
        assertEquals(setOf("200", "401"), responses.keys)
        assertBareApiError(responses.getValue("401"))
    }

    @Test
    fun `the access-derived 403 merges with the one the row declares`() {
        val doc = withAccess(RouteAccess.HasRole(Role.ADMIN), ApiBody(HTTP_FORBIDDEN, ApiErrorSchema::class))
        val responses = responses(doc, SYNTHETIC, "get")
        assertEquals(setOf("200", "401", "403"), responses.keys)
        assertBareApiError(responses.getValue("403"))
        assertBareApiError(responses.getValue("401"))
    }

    @Test
    fun `two classes at one status become a oneOf`() {
        val doc =
            withAccess(
                RouteAccess.Anonymous,
                ApiBody(HTTP_CONFLICT, ApiErrorSchema::class),
                ApiBody(HTTP_CONFLICT, CheckNowCooldownDto::class),
            )
        val conflict = responses(doc, SYNTHETIC, "get").getValue("409")
        assertEquals(
            listOf(API_ERROR_REF, "${SCHEMA_PREFIX}CheckNowCooldownDto"),
            schemaOf(conflict)["oneOf"]!!.jsonArray.map(::schemaRefOf),
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

    private fun assertBareApiError(response: JsonElement) {
        assertEquals(API_ERROR_REF, schemaRef(response))
        assertNull(schemaOf(response)["oneOf"], "the row's entry and the access level's are one class, so no oneOf")
    }

    /** One synthetic GET row at [SYNTHETIC], under [access], declaring exactly [errors]. */
    private fun withAccess(
        access: RouteAccess,
        vararg errors: ApiBody,
    ): OpenApiDoc =
        OpenApiContractDocument.apply(
            docWith(SYNTHETIC to PathItem(get = Operation())),
            listOf(
                ApiEndpoint(
                    ApiMethod.GET,
                    SYNTHETIC,
                    success = ApiBody(HTTP_OK, BuildInfoDto::class),
                    errors = errors.toList(),
                ),
            ),
        ) { _, _ -> access }

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

    private fun responses(
        doc: OpenApiDoc,
        path: String,
        verb: String,
    ): JsonObject = operation(doc, path, verb).getValue("responses").jsonObject

    /** One entry of a `oneOf` list, which is a reference and not a media type. */
    private fun schemaRefOf(schema: JsonElement): String =
        schema.jsonObject
            .getValue("\$ref")
            .jsonPrimitive.content
}
