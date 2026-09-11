package ca.floo.roadtrip.route.common

/**
 * The paging bounds every list endpoint shares. Two route files stated the same
 * five numbers; a third would have made it three.
 */
internal object ListPaging {
    const val DEFAULT_LIMIT = 100
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 500
    const val DEFAULT_OFFSET = 0
    const val MIN_OFFSET = 0

    val limitRange: IntRange = MIN_LIMIT..MAX_LIMIT
}
