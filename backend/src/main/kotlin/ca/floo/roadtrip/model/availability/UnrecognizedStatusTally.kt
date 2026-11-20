package ca.floo.roadtrip.model.availability

class UnrecognizedStatusTally {
    private val cells = linkedMapOf<String, Int>()

    fun orUnknown(
        raw: String,
        status: AvailabilityStatus?,
    ): AvailabilityStatus = status ?: AvailabilityStatus.UNKNOWN.also { add(raw, 1) }

    fun addAll(counts: Map<String, Int>) {
        counts.forEach { (raw, count) -> add(raw, count) }
    }

    fun counts(): Map<String, Int> = cells.toMap()

    private fun add(
        raw: String,
        count: Int,
    ) {
        cells[raw] = (cells[raw] ?: 0) + count
    }
}
