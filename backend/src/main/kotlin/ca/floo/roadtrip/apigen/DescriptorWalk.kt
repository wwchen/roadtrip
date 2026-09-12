package ca.floo.roadtrip.apigen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal

private const val JSON_PACKAGE = "kotlinx.serialization.json."
private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"
private const val JSON_OBJECT = "kotlinx.serialization.json.JsonObject"
private const val JSON_ARRAY = "kotlinx.serialization.json.JsonArray"
private const val JSON_PRIMITIVE = "kotlinx.serialization.json.JsonPrimitive"

/** A list descriptor has one element; a map descriptor's are (key, value). */
private const val LIST_ELEMENT = 0

/** A value class carries exactly one element: the value it is encoded as. */
private const val INLINE_ELEMENT = 0
private const val MAP_KEY_ELEMENT = 0
private const val MAP_VALUE_ELEMENT = 1

/**
 * What a generated declaration may be called. Stricter than a bare TypeScript
 * identifier on purpose: a type name starts with a capital, so a serial name
 * that would emit `export interface my-dto` (invalid) or `export interface
 * campsite` (legal but not a type name) is refused rather than written out.
 */
@Suppress("TopLevelPropertyNaming")
private val DECLARED_NAME = Regex("^[A-Z][A-Za-z0-9_]*$")

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

    /**
     * Walks [descriptor] and everything it reaches; returns its wire type.
     * [within] is the `Class.field` this descriptor was reached through, so a
     * refusal deep inside a collection still names the DTO.
     */
    fun typeOf(
        descriptor: SerialDescriptor,
        within: String = descriptor.serialName,
    ): WireType {
        val target = descriptor.nonNullOriginal
        embeddedJsonType(target)?.let { return it }
        // A value class is encoded as the one value it wraps, so that is its wire type.
        if (target.isInline) return typeOf(target.getElementDescriptor(INLINE_ELEMENT), within)
        return when (val kind = target.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> WireType.Str
            PrimitiveKind.BOOLEAN -> WireType.Bool
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                WireType.Num(integer = true)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> WireType.Num(integer = false)
            StructureKind.LIST -> WireType.ArrayOf(elementType(target, LIST_ELEMENT, within))
            StructureKind.MAP ->
                WireType.MapOf(mapKeyType(target, within), elementType(target, MAP_VALUE_ELEMENT, within))
            SerialKind.ENUM -> WireType.EnumRef(enumType(target))
            StructureKind.CLASS, StructureKind.OBJECT -> WireType.Ref(interfaceType(target))
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

    /**
     * The element of a list, or the value of a map. `explicitNulls = false`
     * omits a null *property*; it says nothing about a null sitting inside an
     * array or a map value, so a nullable element has no honest TypeScript.
     */
    private fun elementType(
        container: SerialDescriptor,
        index: Int,
        within: String,
    ): WireType {
        val element = container.getElementDescriptor(index)
        if (element.isNullable) {
            throw ApiTypeGenerationException(
                "$within has a nullable element type (${element.serialName}). The encoder's " +
                    "explicitNulls = false drops a null property, but leaves a null inside an array " +
                    "or a map value on the wire, so either TypeScript would be a guess. Make the " +
                    "element non-null.",
            )
        }
        return typeOf(element, within)
    }

    /**
     * A JSON object key is a string, so a primitive key renders `string`; an enum
     * key renders the union the generator already knows. Anything else —
     * `CLASS`, `OBJECT`, a nested `LIST` — is what `roadtripApiJson` refuses at
     * runtime (`allowStructuredMapKeys` is off), so it is refused here instead of
     * generating TypeScript for a body the encoder will 500 on.
     */
    private fun mapKeyType(
        container: SerialDescriptor,
        within: String,
    ): WireType {
        val key = container.getElementDescriptor(MAP_KEY_ELEMENT)
        return when (val kind = key.nonNullOriginal.kind) {
            is PrimitiveKind -> WireType.Str
            SerialKind.ENUM -> WireType.EnumRef(enumType(key.nonNullOriginal))
            else ->
                throw ApiTypeGenerationException(
                    "$within is a map keyed by ${key.serialName}, whose serial kind is $kind. JSON " +
                        "object keys are strings, and roadtripApiJson refuses a structured key. Key " +
                        "the map by a primitive or an enum.",
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
                    type =
                        typeOf(
                            descriptor.getElementDescriptor(index),
                            "${descriptor.serialName}.${descriptor.getElementName(index)}",
                        ),
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
     *
     * The one annotation that invalidates this — `@EncodeDefault(NEVER)`, which
     * makes the encoder omit a key the descriptor still calls non-nullable — is
     * not a `@SerialInfo` annotation, so it reaches neither
     * `getElementAnnotations` nor `isElementOptional` and cannot be refused from
     * here. `LayeringGuardTest` keeps it out of `model/` instead.
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
        if (!DECLARED_NAME.matches(name)) {
            throw ApiTypeGenerationException(
                "$serialName would generate the TypeScript declaration name '$name', which is not a " +
                    "legal type name. Give the class a @SerialName whose last segment is a " +
                    "capitalised identifier.",
            )
        }
        val owner = declaredBy.putIfAbsent(name, serialName)
        if (owner != null && owner != serialName) {
            throw ApiTypeGenerationException(
                "Two classes would generate the TypeScript name '$name': $owner and $serialName. " +
                    "Rename one, or give it a @SerialName that disambiguates.",
            )
        }
        return name
    }

    private fun embeddedJsonType(descriptor: SerialDescriptor): WireType? {
        if (!descriptor.serialName.startsWith(JSON_PACKAGE)) return null
        return when (descriptor.serialName) {
            JSON_ELEMENT -> WireType.Any
            JSON_OBJECT -> WireType.AnyObject
            JSON_ARRAY -> WireType.AnyArray
            JSON_PRIMITIVE -> WireType.Primitive
            else ->
                throw ApiTypeGenerationException(
                    "${descriptor.serialName} is a kotlinx.serialization.json type with no TypeScript " +
                        "mapping. JsonElement, JsonObject, JsonArray and JsonPrimitive are the four that map.",
                )
        }
    }
}
