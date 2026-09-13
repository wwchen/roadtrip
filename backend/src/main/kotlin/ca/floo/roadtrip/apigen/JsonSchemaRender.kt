package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiEndpoint
import io.ktor.openapi.AdditionalProperties
import io.ktor.openapi.GenericElementString
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.ReferenceOr

/** What a `JsonPrimitive` can be on the wire; `SchemaType.AnyOf` renders it as a type list. */
private val jsonPrimitiveTypes = listOf(JsonType.STRING, JsonType.NUMBER, JsonType.BOOLEAN)

/**
 * A generated interface accepts only the keys it declares.
 *
 * Exactly true of a response: the one encoder writes the declared elements and
 * nothing else. For a request it is the contract's *statement* rather than the
 * decoder's behaviour — `roadtripApiJson` has `ignoreUnknownKeys = true`, so an
 * extra key is tolerated at runtime and promised nothing.
 */
private val noExtraProperties: AdditionalProperties = AdditionalProperties.Allowed(false)

/**
 * One [WireType] as a JSON Schema, or as a `$ref` to one.
 *
 * A declared class or enum is always a reference, never inlined, so
 * `components/schemas/CampsiteDto` and `interface CampsiteDto` stay one
 * declaration rendered twice.
 *
 * No arm ever emits `type: null`. The encoder never writes a Kotlin null, and
 * the four `Json*` arms — which *can* carry a `JsonNull` inside them — are
 * deliberately loose rather than nullable: `Any` is the empty schema, and
 * `AnyObject`/`AnyArray`/`Primitive` constrain only the outer shape.
 */
internal fun jsonSchemaOf(type: WireType): ReferenceOr<JsonSchema> =
    when (type) {
        is WireType.Ref -> ReferenceOr.schema(type.name)
        is WireType.EnumRef -> ReferenceOr.schema(type.name)
        WireType.Str -> ReferenceOr.value(JsonSchema(type = JsonType.STRING))
        is WireType.Num ->
            ReferenceOr.value(JsonSchema(type = if (type.integer) JsonType.INTEGER else JsonType.NUMBER))
        WireType.Bool -> ReferenceOr.value(JsonSchema(type = JsonType.BOOLEAN))
        WireType.Any -> ReferenceOr.value(JsonSchema())
        WireType.AnyObject -> ReferenceOr.value(JsonSchema(type = JsonType.OBJECT))
        WireType.AnyArray -> ReferenceOr.value(JsonSchema(type = JsonType.ARRAY))
        WireType.Primitive ->
            ReferenceOr.value(JsonSchema(type = JsonSchema.SchemaType.AnyOf(jsonPrimitiveTypes)))
        is WireType.ArrayOf ->
            ReferenceOr.value(JsonSchema(type = JsonType.ARRAY, items = jsonSchemaOf(type.element)))
        // JsonSchema carries no `propertyNames`, so an enum key's union is documented rather than declared.
        is WireType.MapOf ->
            ReferenceOr.value(
                JsonSchema(
                    type = JsonType.OBJECT,
                    additionalProperties = AdditionalProperties.PSchema(jsonSchemaOf(type.value)),
                ),
            )
    }

/**
 * Every declaration [walk] found, as a `components/schemas` map keyed by the
 * same claimed name the TypeScript declares, ordered by name so two runs over an
 * unchanged tree serve an identical document.
 */
internal fun schemasOf(walk: ContractWalk): Map<String, JsonSchema> =
    (walk.enums.map { it.name to enumSchema(it) } + walk.interfaces.map { it.name to interfaceSchema(it) })
        .sortedBy { it.first }
        .toMap(LinkedHashMap())

/** `components/schemas` for [endpoints]: one walk, both views. */
internal fun contractSchemas(endpoints: List<ApiEndpoint> = ApiContract.endpoints): Map<String, JsonSchema> =
    schemasOf(walkContract(endpoints))

/**
 * `required` is the complement of the walk's optionality, under whichever rule
 * the walk applied — nullability alone for a response, nullability or a default
 * for a request. An empty set is omitted rather than written as `[]`.
 */
private fun interfaceSchema(declared: TsInterface): JsonSchema =
    JsonSchema(
        type = JsonType.OBJECT,
        title = declared.name,
        required =
            declared.fields
                .filterNot { it.optional }
                .map { it.name }
                .takeIf { it.isNotEmpty() },
        properties = declared.fields.associate { it.name to jsonSchemaOf(it.type) }.takeIf { it.isNotEmpty() },
        additionalProperties = noExtraProperties,
    )

private fun enumSchema(declared: TsEnum): JsonSchema =
    JsonSchema(
        type = JsonType.STRING,
        title = declared.name,
        enum = declared.values.map { GenericElementString(it) },
    )
