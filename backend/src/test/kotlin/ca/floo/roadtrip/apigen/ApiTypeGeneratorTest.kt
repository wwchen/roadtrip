package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiMethod
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
enum class FixtureFlavour(
    val wireValue: String,
) {
    @SerialName("sweet")
    SWEET("sweet"),

    @SerialName("sour")
    SOUR("sour"),
}

@Serializable
data class FixtureScalars(
    val text: String,
    val letter: Char,
    val count: Int,
    val big: Long,
    val ratio: Double,
    val flag: Boolean,
)

/** A value class: no declaration of its own, just the primitive kind its descriptor reports. */
@JvmInline
@Serializable
value class FixtureId(
    val raw: Long,
)

@Serializable
data class FixtureShapes(
    val names: List<String>,
    val tags: Set<String>,
    @SerialName("by_id") val byId: Map<Long, FixtureScalars>,
    val flavour: FixtureFlavour,
    val blob: JsonElement,
    val bag: JsonObject,
    val rows: JsonArray,
    val scalar: JsonPrimitive,
    val id: FixtureId,
)

@Serializable
data class FixtureOddKeys(
    @SerialName("odd-key") val oddKey: String,
)

@Serializable
data class FixtureUnmappedJson(
    val nothing: JsonNull,
)

/** Not `@Serializable`: the field below only reaches it through a contextual serializer. */
class FixtureOpaque

@Serializable
data class FixtureContextual(
    @Contextual val opaque: FixtureOpaque,
)

@Serializable
data class FixtureNullableElement(
    val names: List<String?>,
)

@Serializable
data class FixtureNullableMapValue(
    @SerialName("by_id") val byId: Map<String, FixtureScalars?>,
)

/** A serial name whose generated spelling is not a legal TypeScript type name. */
@Serializable
@SerialName("my-dto")
data class FixtureIllegalName(
    val x: String,
)

@Serializable
data class FixtureOptionality(
    val required: String,
    val nullable: String? = null,
    val defaulted: Int = 3,
    @SerialName("nullable_no_default") val nullableNoDefault: String?,
)

@Serializable
data class FixtureNested(
    val inner: FixtureScalars?,
)

@Serializable
sealed interface FixtureSealed {
    @Serializable
    data class One(
        val a: String,
    ) : FixtureSealed
}

/** Same simple name as [FixtureScalars] under a different serial name: a collision. */
@Serializable
@SerialName("ca.floo.roadtrip.apigen.other.FixtureScalars")
data class FixtureCollider(
    val x: String,
)

/**
 * The generator, against a fixture DTO set that exercises every row of the
 * mapping table and every failure it is supposed to refuse.
 */
class ApiTypeGeneratorTest {
    @Test
    fun `the mapping table`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureShapes::class)))
        assertBlock(
            ts,
            """
            export interface FixtureScalars {
              text: string;
              letter: string;
              count: number;
              big: number;
              ratio: number;
              flag: boolean;
            }
            """.trimIndent(),
        )
        assertBlock(
            ts,
            """
            export interface FixtureShapes {
              names: string[];
              tags: string[];
              by_id: Record<string, FixtureScalars>;
              flavour: FixtureFlavour;
              blob: unknown;
              bag: Record<string, unknown>;
              rows: unknown[];
              scalar: string | number | boolean;
              id: number;
            }
            """.trimIndent(),
        )
        assertTrue(ts.contains("export type FixtureFlavour = 'sweet' | 'sour';"), ts)
    }

    @Test
    fun `a wire key that is not an identifier is quoted`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureOddKeys::class)))
        assertBlock(
            ts,
            """
            export interface FixtureOddKeys {
              'odd-key': string;
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `response optionality follows nullability alone`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureOptionality::class)))
        assertBlock(
            ts,
            """
            export interface FixtureOptionality {
              required: string;
              nullable?: string;
              defaulted: number;
              nullable_no_default?: string;
            }
            """.trimIndent(),
        )
        assertFalse(ts.contains("| null"), "the encoder never emits null, so no arm may say so")
    }

    @Test
    fun `request optionality also honours defaults`() {
        val ts = generateApiTypes(listOf(requestRow(FixtureOptionality::class)))
        assertBlock(
            ts,
            """
            export interface FixtureOptionality {
              required: string;
              nullable?: string;
              defaulted?: number;
              nullable_no_default?: string;
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a nullable nested class is still walked`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureNested::class)))
        assertBlock(
            ts,
            """
            export interface FixtureNested {
              inner?: FixtureScalars;
            }
            """.trimIndent(),
        )
        assertTrue(ts.contains("export interface FixtureScalars {"), ts)
    }

    @Test
    fun `a class used as both request and response must agree on optionality`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureOptionality::class), requestRow(FixtureOptionality::class)))
            }
        assertTrue(failure.message!!.contains("FixtureOptionality"), failure.message!!)
        assertTrue(failure.message!!.contains("defaulted"), failure.message!!)
    }

    @Test
    fun `two classes with the same simple name fail generation`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureScalars::class), responseRow(FixtureCollider::class)))
            }
        assertTrue(failure.message!!.contains("FixtureScalars"), failure.message!!)
    }

    @Test
    fun `a sealed type fails generation by name`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> { generateApiTypes(listOf(responseRow(FixtureSealed::class))) }
        assertTrue(failure.message!!.contains("FixtureSealed"), failure.message!!)
        assertTrue(failure.message!!.contains("sealed or polymorphic"), failure.message!!)
    }

    @Test
    fun `an unmapped kotlinx json type fails generation by name`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureUnmappedJson::class)))
            }
        assertTrue(failure.message!!.contains("kotlinx.serialization.json.JsonNull"), failure.message!!)
        assertTrue(
            failure.message!!.contains("JsonElement, JsonObject, JsonArray and JsonPrimitive"),
            failure.message!!,
        )
    }

    @Test
    fun `a contextual descriptor fails generation by name`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureContextual::class)))
            }
        assertTrue(failure.message!!.contains("CONTEXTUAL"), failure.message!!)
        assertTrue(failure.message!!.contains("no TypeScript mapping"), failure.message!!)
    }

    @Test
    fun `a nullable list element fails generation, naming the field`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureNullableElement::class)))
            }
        assertTrue(failure.message!!.contains("FixtureNullableElement.names"), failure.message!!)
        assertTrue(failure.message!!.contains("nullable element type"), failure.message!!)
    }

    @Test
    fun `a nullable map value fails generation, naming the field`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureNullableMapValue::class)))
            }
        assertTrue(failure.message!!.contains("FixtureNullableMapValue.by_id"), failure.message!!)
        assertTrue(failure.message!!.contains("nullable element type"), failure.message!!)
    }

    @Test
    fun `a serial name that is not a legal type name fails generation`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureIllegalName::class)))
            }
        assertTrue(failure.message!!.contains("my-dto"), failure.message!!)
        assertTrue(failure.message!!.contains("not a legal type name"), failure.message!!)
    }

    @Test
    fun `output is deterministic, sorted by name, LF only, newline terminated`() {
        val rows = listOf(responseRow(FixtureShapes::class), responseRow(FixtureNested::class))
        val first = generateApiTypes(rows)
        assertEquals(first, generateApiTypes(rows))
        val declared =
            Regex("^export (?:interface|type) (\\w+)", RegexOption.MULTILINE)
                .findAll(first)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(declared.sorted(), declared, "declarations must be sorted by name")
        assertTrue(first.endsWith("\n"), "the file must end with a newline")
        assertFalse(first.contains("\r"), "the file must be LF only")
    }

    @Test
    fun `the endpoint literal names the request and response types`() {
        val ts =
            generateApiTypes(
                listOf(
                    ApiEndpoint(
                        ApiMethod.POST,
                        "/api/fixtures",
                        FixtureOptionality::class,
                        FixtureShapes::class,
                        errors = emptyList(),
                    ),
                    responseRow(FixtureNested::class),
                ),
            )
        assertTrue(
            ts.contains("{ method: 'POST', path: '/api/fixtures', request: 'FixtureOptionality', response: 'FixtureShapes' },"),
            ts,
        )
        assertTrue(
            ts.contains("{ method: 'GET', path: '/api/fixture/FixtureNested', request: null, response: 'FixtureNested' },"),
            ts,
        )
        assertTrue(ts.trimEnd().endsWith("] as const;"), ts)
    }

    @Test
    fun `the real contract generates the types this phase exists for`() {
        val ts = generateApiTypes()
        assertTrue(ts.contains("export interface CampsiteDto {"), "CampsiteDto missing")
        assertTrue(ts.contains("  booking_provider?: string;"), "the campsite booking_provider field is missing")
        assertTrue(ts.contains("export type WatchStatus = 'active' | 'paused' | 'done';"), "the watch status union is missing")
        assertTrue(ts.contains("export type WatchDoneReason = 'triggered' | 'elapsed';"), "the done reason union is missing")
        assertTrue(ts.contains("  region?: string;"), "the search hit's region is missing")
    }

    private fun responseRow(kClass: KClass<*>): ApiEndpoint =
        ApiEndpoint(ApiMethod.GET, "/api/fixture/${kClass.simpleName}", response = kClass, errors = emptyList())

    private fun requestRow(kClass: KClass<*>): ApiEndpoint =
        ApiEndpoint(ApiMethod.POST, "/api/fixture/${kClass.simpleName}", request = kClass, errors = emptyList())

    private fun assertBlock(
        generated: String,
        block: String,
    ) {
        assertTrue(generated.contains(block), "expected:\n$block\n\nin:\n$generated")
    }
}
