package ca.floo.roadtrip.route.common

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer

/**
 * The wire names of enum [E]'s entries, in declaration order, as the serializer
 * writes them. For text that states a vocabulary (an API description), so it is
 * read from the enum rather than typed beside it and left to drift.
 */
@OptIn(ExperimentalSerializationApi::class)
internal inline fun <reified E : Enum<E>> wireNames(): List<String> =
    serializer<E>().descriptor.let { descriptor -> (0 until descriptor.elementsCount).map(descriptor::getElementName) }

/** This entry's wire name, as [wireNames] lists it. */
internal inline fun <reified E : Enum<E>> E.wireName(): String = wireNames<E>()[ordinal]

/** [wireNames] as code spans joined by ", ", e.g. "`open`, `few_left`". */
internal inline fun <reified E : Enum<E>> wireNameList(): String = wireNames<E>().joinToString(", ") { "`$it`" }
