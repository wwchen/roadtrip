package ca.floo.roadtrip.apigen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal

private const val TS_STRING = "string"
private const val TS_NUMBER = "number"
private const val TS_BOOLEAN = "boolean"
private const val TS_UNKNOWN = "unknown"
private const val TS_UNKNOWN_RECORD = "Record<string, unknown>"
private const val TS_UNKNOWN_ARRAY = "unknown[]"
private const val TS_JSON_PRIMITIVE = "string | number | boolean"

private const val JSON_PACKAGE = "kotlinx.serialization.json."
private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"
private const val JSON_OBJECT = "kotlinx.serialization.json.JsonObject"
private const val JSON_ARRAY = "kotlinx.serialization.json.JsonArray"
private const val JSON_PRIMITIVE = "kotlinx.serialization.json.JsonPrimitive"

/** A map descriptor's elements are (key, value); JSON object keys are always strings. */
private const val MAP_VALUE_ELEMENT = 1

/** Which side of the wire a walk describes. It decides optionality, nothing else. */
internal enum class Optionality { RESPONSE, REQUEST }

/**
 * The TypeScript name for a serial name: drop the package segments (the
 * lowercase-initial ones) and join what is left, so a nested
 * `…ReadinessResponseDto.State` becomes `ReadinessResponseDtoState` rather than
 * a bare `State` that would collide with the next nested enum.
 */
internal fun tsName(serialName: String): String =
    serialName
        .split('.')
        .dropWhile { it.isEmpty() || it.first().isLowerCase() }
        .joinToString("")
        .ifEmpty { serialName.substringAfterLast('.') }

/**
 * One walk of the descriptor graph under one [Optionality].
 *
 * [declaredBy] is shared between the response walk and the request walk, so two
 * classes that would generate the same TypeScript name fail wherever the second
 * one is first reached.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class DescriptorWalk(
    private val optionality: Optionality,
    private val declaredBy: MutableMap<String, String>,
) {
    val interfaces: MutableMap<String, TsInterface> = LinkedHashMap()
    val enums: MutableMap<String, TsEnum> = LinkedHashMap()
    private val inProgress = HashSet<String>()

    /** Walks [descriptor] and everything it reaches; returns its TypeScript spelling. */
    fun typeOf(descriptor: SerialDescriptor): String {
        val target = descriptor.nonNullOriginal
        embeddedJsonType(target)?.let { return it }
        return when (val kind = target.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> TS_STRING
            PrimitiveKind.BOOLEAN -> TS_BOOLEAN
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT,
            PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
            -> TS_NUMBER
            StructureKind.LIST -> "${typeOf(target.getElementDescriptor(0))}[]"
            StructureKind.MAP -> "Record<string, ${typeOf(target.getElementDescriptor(MAP_VALUE_ELEMENT))}>"
            SerialKind.ENUM -> enumType(target)
            StructureKind.CLASS, StructureKind.OBJECT -> interfaceType(target)
            is PolymorphicKind ->
                throw ApiTypeGenerationException(
                    "${target.serialName} is sealed or polymorphic. Nothing on the wire is today, and " +
                        "the discriminator mapping is undesigned; design it before serving this type.",
                )
            else ->
                throw ApiTypeGenerationException(
                    "${target.serialName} has serial kind $kind, which has no TypeScript mapping.",
                )
        }
    }

    private fun interfaceType(descriptor: SerialDescriptor): String {
        val name = claimName(descriptor.serialName)
        if (interfaces.containsKey(descriptor.serialName)) return name
        // A self-referential type would recurse forever; the name is enough for the cycle.
        if (!inProgress.add(descriptor.serialName)) return name
        val fields =
            (0 until descriptor.elementsCount).map { index ->
                TsField(
                    name = descriptor.getElementName(index),
                    type = typeOf(descriptor.getElementDescriptor(index)),
                    optional = isOptional(descriptor, index),
                )
            }
        inProgress.remove(descriptor.serialName)
        interfaces[descriptor.serialName] = TsInterface(name, fields)
        return name
    }

    private fun enumType(descriptor: SerialDescriptor): String {
        val name = claimName(descriptor.serialName)
        enums.getOrPut(descriptor.serialName) {
            TsEnum(name, (0 until descriptor.elementsCount).map(descriptor::getElementName))
        }
        return name
    }

    /**
     * The encoder is `encodeDefaults = true, explicitNulls = false`: a response
     * omits exactly the nulls, so only a nullable field is optional. A request is
     * decoded, so a default also makes the element absent-tolerant.
     */
    private fun isOptional(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        val nullable = descriptor.getElementDescriptor(index).isNullable
        return when (optionality) {
            Optionality.RESPONSE -> nullable
            Optionality.REQUEST -> nullable || descriptor.isElementOptional(index)
        }
    }

    private fun claimName(serialName: String): String {
        val name = tsName(serialName)
        val owner = declaredBy.putIfAbsent(name, serialName)
        if (owner != null && owner != serialName) {
            throw ApiTypeGenerationException(
                "Two classes would generate the TypeScript name '$name': $owner and $serialName. " +
                    "Rename one, or give it a @SerialName that disambiguates.",
            )
        }
        return name
    }

    private fun embeddedJsonType(descriptor: SerialDescriptor): String? {
        if (!descriptor.serialName.startsWith(JSON_PACKAGE)) return null
        return when (descriptor.serialName) {
            JSON_ELEMENT -> TS_UNKNOWN
            JSON_OBJECT -> TS_UNKNOWN_RECORD
            JSON_ARRAY -> TS_UNKNOWN_ARRAY
            JSON_PRIMITIVE -> TS_JSON_PRIMITIVE
            else ->
                throw ApiTypeGenerationException(
                    "${descriptor.serialName} is a kotlinx.serialization.json type with no TypeScript " +
                        "mapping. JsonElement, JsonObject, JsonArray and JsonPrimitive are the four that map.",
                )
        }
    }
}
