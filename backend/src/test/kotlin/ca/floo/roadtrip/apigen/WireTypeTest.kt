package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The walk's structured output, and the TypeScript renderer over it.
 *
 * The mapping table itself is asserted as *text* by [ApiTypeGeneratorTest]; this
 * fixes the shape the schema renderer reads, so a later change that keeps the
 * text identical while flattening the structure fails here rather than silently
 * costing `/api/docs` its `integer` types.
 */
class WireTypeTest {
    @Test
    fun `the walk reports structure, not text`() {
        val walk = DescriptorWalk(Optionality.RESPONSE, HashMap())
        walk.typeOf(descriptorOf(FixtureShapes::class))
        val shapes = walk.interfaces.getValue(FixtureShapes::class.qualifiedName!!)
        assertEquals(
            listOf(
                TsField("names", WireType.ArrayOf(WireType.Str), optional = false),
                TsField("tags", WireType.ArrayOf(WireType.Str), optional = false),
                TsField("by_id", WireType.MapOf(WireType.Str, WireType.Ref("FixtureScalars")), optional = false),
                TsField("flavour", WireType.EnumRef("FixtureFlavour"), optional = false),
                TsField("blob", WireType.Any, optional = false),
                TsField("bag", WireType.AnyObject, optional = false),
                TsField("rows", WireType.AnyArray, optional = false),
                TsField("scalar", WireType.Primitive, optional = false),
                TsField("id", WireType.Num(integer = true), optional = false),
            ),
            shapes.fields,
        )
    }

    @Test
    fun `an integer and a fractional number are distinct in the walk and identical in TypeScript`() {
        val walk = DescriptorWalk(Optionality.RESPONSE, HashMap())
        walk.typeOf(descriptorOf(FixtureScalars::class))
        val scalars = walk.interfaces.getValue(FixtureScalars::class.qualifiedName!!)
        assertEquals(
            listOf(
                WireType.Str,
                WireType.Str,
                WireType.Num(integer = true),
                WireType.Num(integer = true),
                WireType.Num(integer = false),
                WireType.Bool,
            ),
            scalars.fields.map { it.type },
        )
        assertEquals("number", tsTypeOf(WireType.Num(integer = true)))
        assertEquals("number", tsTypeOf(WireType.Num(integer = false)))
    }

    @Test
    fun `an enum-keyed map keeps the enum in the key position`() {
        val walk = DescriptorWalk(Optionality.RESPONSE, HashMap())
        walk.typeOf(descriptorOf(FixtureEnumKeyMap::class))
        val map =
            walk.interfaces
                .getValue(FixtureEnumKeyMap::class.qualifiedName!!)
                .fields
                .single()
                .type
        assertEquals(WireType.MapOf(WireType.EnumRef("FixtureFlavour"), WireType.Str), map)
        assertEquals("Record<FixtureFlavour, string>", tsTypeOf(map))
    }

    @Test
    fun `every mapping-table arm renders the text the generated file already carries`() {
        assertEquals("string", tsTypeOf(WireType.Str))
        assertEquals("boolean", tsTypeOf(WireType.Bool))
        assertEquals("unknown", tsTypeOf(WireType.Any))
        assertEquals("Record<string, unknown>", tsTypeOf(WireType.AnyObject))
        assertEquals("unknown[]", tsTypeOf(WireType.AnyArray))
        assertEquals("string | number | boolean", tsTypeOf(WireType.Primitive))
        assertEquals("CampsiteDto", tsTypeOf(WireType.Ref("CampsiteDto")))
        assertEquals("WatchStatus", tsTypeOf(WireType.EnumRef("WatchStatus")))
        assertEquals("CampsiteDto[]", tsTypeOf(WireType.ArrayOf(WireType.Ref("CampsiteDto"))))
        assertEquals(
            "Record<string, CampsiteDto[]>",
            tsTypeOf(WireType.MapOf(WireType.Str, WireType.ArrayOf(WireType.Ref("CampsiteDto")))),
        )
    }

    @Test
    fun `the contract walk hands back rows in declaration order`() {
        assertEquals(
            ApiContract.endpoints.map { it.path },
            walkContract().rows.map { it.path },
            "walkContract must not sort: the document builder zips its rows against ApiContract.endpoints",
        )
    }

    private fun descriptorOf(kClass: KClass<*>) = serializer(kClass.createType()).descriptor
}
