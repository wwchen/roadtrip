package ca.floo.roadtrip.service.ref

import kotlin.reflect.KClass

interface RefResolver {
    fun <T : RefValue> resolve(
        from: RefValue,
        to: KClass<T>,
    ): List<T>

    fun <T : RefValue> resolve(
        from: List<RefValue>,
        to: KClass<T>,
    ): Map<RefValue, List<T>>
}

inline fun <reified T : RefValue> RefResolver.resolve(from: RefValue): List<T> = resolve(from, T::class)

inline fun <reified T : RefValue> RefResolver.resolve(from: List<RefValue>): Map<RefValue, List<T>> = resolve(from, T::class)
