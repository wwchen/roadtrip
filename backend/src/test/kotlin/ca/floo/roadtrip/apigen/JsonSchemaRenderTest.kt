package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiBody
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.HTTP_NO_CONTENT
import ca.floo.roadtrip.model.api.HTTP_OK
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.ReferenceOr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON-Schema view of the walk, asserted as what `roadtripApiJson` actually
 * writes — the encoder the document is served through, whose `explicitNulls =
 * false` is what turns a 39-property data class into a two-key schema.
 */
class JsonSchemaRenderTest {
    @Test
    fun `every scalar arm of the mapping table`() {
        assertSchema("""{"type":"string"}""", WireType.Str)
        assertSchema("""{"type":"integer"}""", WireType.Num(integer = true))
        assertSchema("""{"type":"number"}""", WireType.Num(integer = false))
        assertSchema("""{"type":"boolean"}""", WireType.Bool)
        assertSchema("{}", WireType.Any)
        assertSchema("""{"type":"object"}""", WireType.AnyObject)
        assertSchema("""{"type":"array"}""", WireType.AnyArray)
        assertSchema("""{"type":["string","number","boolean"]}""", WireType.Primitive)
    }

    @Test
    fun `a declared class or enum is a component reference, never inlined`() {
        assertSchema("""{"${'$'}ref":"#/components/schemas/CampsiteDto"}""", WireType.Ref("CampsiteDto"))
        assertSchema("""{"${'$'}ref":"#/components/schemas/WatchStatus"}""", WireType.EnumRef("WatchStatus"))
    }

    @Test
    fun `a list carries its element schema in items`() {
        assertSchema(
            """{"type":"array","items":{"${'$'}ref":"#/components/schemas/CampsiteDto"}}""",
            WireType.ArrayOf(WireType.Ref("CampsiteDto")),
        )
        assertSchema("""{"type":"array","items":{"type":"string"}}""", WireType.ArrayOf(WireType.Str))
    }

    @Test
    fun `a map carries its value schema in additionalProperties`() {
        assertSchema(
            """{"type":"object","additionalProperties":{"${'$'}ref":"#/components/schemas/CampsiteDto"}}""",
            WireType.MapOf(WireType.Str, WireType.Ref("CampsiteDto")),
        )
    }

    /**
     * `JsonSchema` has no `propertyNames`, so the key union an enum-keyed map
     * guarantees is not expressible in this model. The value schema is; the key
     * constraint is documented in `docs/backend-architecture.md` instead. This
     * test pins that fact so a Ktor upgrade that adds the field is noticed.
     */
    @Test
    fun `an enum-keyed map constrains only its values`() {
        assertSchema(
            """{"type":"object","additionalProperties":{"type":"string"}}""",
            WireType.MapOf(WireType.EnumRef("FixtureFlavour"), WireType.Str),
        )
    }

    @Test
    fun `an interface declares its properties, its required set and no extras`() {
        assertEquals(
            parsed(
                """
                {
                  "type": "object",
                  "title": "FixtureScalars",
                  "required": ["text", "letter", "count", "big", "ratio", "flag"],
                  "properties": {
                    "text": {"type": "string"},
                    "letter": {"type": "string"},
                    "count": {"type": "integer"},
                    "big": {"type": "integer"},
                    "ratio": {"type": "number"},
                    "flag": {"type": "boolean"}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
            ),
            encoded(schemaFor(FixtureScalars::class).getValue("FixtureScalars")),
        )
    }

    @Test
    fun `required in a response body is the complement of nullability alone`() {
        val schema = schemaFor(FixtureOptionality::class).getValue("FixtureOptionality")
        assertEquals(listOf("required", "defaulted"), schema.required)
    }

    @Test
    fun `required in a request body also drops the defaulted fields`() {
        val walk =
            walkContract(
                listOf(
                    ApiEndpoint(
                        ApiMethod.POST,
                        "/api/fixture/FixtureOptionality",
                        request = FixtureOptionality::class,
                        success = ApiBody(HTTP_NO_CONTENT),
                        errors = emptyList(),
                    ),
                ),
            )
        assertEquals(listOf("required"), schemasOf(walk).getValue("FixtureOptionality").required)
    }

    @Test
    fun `an enum is a string with its serial names`() {
        assertEquals(
            parsed("""{"type":"string","title":"FixtureFlavour","enum":["sweet","sour"]}"""),
            encoded(schemaFor(FixtureShapes::class).getValue("FixtureFlavour")),
        )
    }

    @Test
    fun `an optional field is absent from required, not typed nullable`() {
        val schema = schemaFor(FixtureNested::class).getValue("FixtureNested")
        assertEquals(null, schema.required, "every field of FixtureNested is nullable, so nothing is required")
        assertEquals(
            parsed("""{"${'$'}ref":"#/components/schemas/FixtureScalars"}"""),
            encoded(schema.properties!!.getValue("inner")),
        )
    }

    @Test
    fun `the real contract renders a schema for every name the TypeScript declares`() {
        val walk = walkContract()
        val schemas = contractSchemas()
        assertEquals(
            (walk.interfaces.map { it.name } + walk.enums.map { it.name }).toSortedSet(),
            schemas.keys.toSortedSet(),
        )
        assertEquals(walk.interfaces.size + walk.enums.size, schemas.size, "one schema per declaration, no more")
        assertTrue(
            schemas.size > MIN_EXPECTED_SCHEMAS,
            "expected the contract to declare many schemas, found ${schemas.size}",
        )
    }

    @Test
    fun `no schema in the real contract claims a null type`() {
        val nullTyped =
            Json
                .parseToJsonElement(encodeApiJson(contractSchemas()))
                .jsonObject
                .filter { (_, schema) -> NULL_TYPE in typesOf(schema) }
                .keys
        assertEquals(emptySet(), nullTyped, "the one encoder never writes a null, so no schema may claim the type")
    }

    @Test
    fun `the schema map is ordered by name, so the document is deterministic`() {
        val names = contractSchemas().keys.toList()
        assertEquals(names.sorted(), names)
    }

    private fun schemaFor(kClass: KClass<*>): Map<String, JsonSchema> =
        schemasOf(
            walkContract(
                listOf(
                    ApiEndpoint(
                        ApiMethod.GET,
                        "/api/fixture/${kClass.simpleName}",
                        success = ApiBody(HTTP_OK, kClass),
                        errors = emptyList(),
                    ),
                ),
            ),
        )

    private fun assertSchema(
        expected: String,
        type: WireType,
    ) = assertEquals(parsed(expected), encoded(jsonSchemaOf(type)), expected)

    private fun encoded(value: JsonSchema): JsonElement = Json.parseToJsonElement(encodeApiJson(value))

    private fun encoded(value: ReferenceOr<JsonSchema>): JsonElement = Json.parseToJsonElement(encodeApiJson(value))

    private fun parsed(json: String): JsonElement = Json.parseToJsonElement(json).jsonObject

    /**
     * Every `type` this schema declares, at any depth — a nested property's as
     * much as the outer one's. The recursion descends only where a *schema* can
     * sit, because a DTO is free to have a field literally called `type`, whose
     * entry under `properties` is a name and not the keyword.
     */
    private fun typesOf(schema: JsonElement): List<String> {
        val obj = schema as? JsonObject ?: return emptyList()
        val nested =
            listOfNotNull(obj["items"], obj["additionalProperties"], obj["not"]) +
                obj["properties"]?.jsonObject?.values.orEmpty() +
                schemaListKeywords.flatMap { obj[it]?.jsonArray.orEmpty() }
        return typeNames(obj["type"]) + nested.flatMap(::typesOf)
    }

    /** A `type` is absent, one name, or a list of them. */
    private fun typeNames(value: JsonElement?): List<String> =
        when (value) {
            null -> emptyList()
            is JsonArray -> value.map { it.jsonPrimitive.content }
            else -> listOf(value.jsonPrimitive.content)
        }

    private companion object {
        /** Well under the real count; a floor, so the check means "the walk found the contract". */
        const val MIN_EXPECTED_SCHEMAS = 60

        /** The one JSON Schema type name no schema of ours may declare. */
        const val NULL_TYPE = "null"

        /** Keywords whose value is a list of schemas. */
        val schemaListKeywords = listOf("oneOf", "anyOf", "allOf", "prefixItems")
    }
}
