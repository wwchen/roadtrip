package ca.floo.roadtrip.apigen

/**
 * What one wire position is, structurally.
 *
 * The one thing between [DescriptorWalk] and its two renderers: [tsTypeOf] turns
 * it into TypeScript and `JsonSchemaRender` into a JSON Schema, so the generated
 * `.ts` file and the `components/schemas` of `/api/docs` are two views of a
 * single walk with one optionality rule and one set of refusals.
 *
 * [Num.integer] is the one distinction TypeScript cannot carry: both arms render
 * `number`, while JSON Schema distinguishes `integer` from `number`.
 */
internal sealed interface WireType {
    data object Str : WireType

    /** `Int`, `Long`, `Short` and `Byte` are integers; `Float` and `Double` are not. */
    data class Num(
        val integer: Boolean,
    ) : WireType

    data object Bool : WireType

    /** `JsonElement`: any JSON at all, a `JsonNull` included. */
    data object Any : WireType

    /** `JsonObject`. */
    data object AnyObject : WireType

    /** `JsonArray`. */
    data object AnyArray : WireType

    /** `JsonPrimitive`: a string, a number or a boolean. */
    data object Primitive : WireType

    /** A declared interface, by its claimed name. */
    data class Ref(
        val name: String,
    ) : WireType

    /** A declared enum, by its claimed name. */
    data class EnumRef(
        val name: String,
    ) : WireType

    data class ArrayOf(
        val element: WireType,
    ) : WireType

    /**
     * A JSON object whose keys are not declared. [key] is [Str] for any primitive
     * key — a JSON key is a string whatever Kotlin called it — or an [EnumRef]
     * when the map is keyed by an enum; the walk refuses anything else.
     */
    data class MapOf(
        val key: WireType,
        val value: WireType,
    ) : WireType
}
