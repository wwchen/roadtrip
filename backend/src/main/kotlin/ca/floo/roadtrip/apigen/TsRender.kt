package ca.floo.roadtrip.apigen

private const val TS_STRING = "string"

/** Every Kotlin number, including `Double` — and a `Double` on the wire is a *finite* one: the encoder rejects NaN/Infinity. */
private const val TS_NUMBER = "number"
private const val TS_BOOLEAN = "boolean"
private const val TS_UNKNOWN = "unknown"
private const val TS_UNKNOWN_RECORD = "Record<string, unknown>"
private const val TS_UNKNOWN_ARRAY = "unknown[]"
private const val TS_JSON_PRIMITIVE = "string | number | boolean"

/**
 * The TypeScript spelling of one [WireType].
 *
 * Both [WireType.Num] arms render `number`: the ids are database bigints that
 * stay well below 2^53, and TypeScript has no integer type to narrow them to.
 */
internal fun tsTypeOf(type: WireType): String =
    when (type) {
        WireType.Str -> TS_STRING
        is WireType.Num -> TS_NUMBER
        WireType.Bool -> TS_BOOLEAN
        WireType.Any -> TS_UNKNOWN
        WireType.AnyObject -> TS_UNKNOWN_RECORD
        WireType.AnyArray -> TS_UNKNOWN_ARRAY
        WireType.Primitive -> TS_JSON_PRIMITIVE
        is WireType.Ref -> type.name
        is WireType.EnumRef -> type.name
        is WireType.ArrayOf -> "${tsTypeOf(type.element)}[]"
        is WireType.MapOf -> "Record<${tsTypeOf(type.key)}, ${tsTypeOf(type.value)}>"
    }
