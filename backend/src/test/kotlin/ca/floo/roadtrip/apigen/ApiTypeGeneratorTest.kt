package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

@Serializable
data class FixtureShapes(
    val names: List<String>,
    val tags: Set<String>,
    @SerialName("by_id") val byId: Map<Long, FixtureScalars>,
    val flavour: FixtureFlavour,
    val blob: JsonElement,
    val bag: JsonObject,
    val rows: JsonArray,
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
            }
            """.trimIndent(),
        )
        assertTrue(ts.contains("export type FixtureFlavour = 'sweet' | 'sour';"), ts)
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
                ),
            )
        assertTrue(
            ts.contains("{ method: 'POST', path: '/api/fixtures', request: 'FixtureOptionality', response: 'FixtureShapes' },"),
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
