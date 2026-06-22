package ca.floo.roadtrip.service.availability

enum class WatchStatus(
    val wireValue: String,
) {
    ACTIVE("active"),
    PAUSED("paused"),
    DONE("done"),
    ;

    companion object {
        fun parse(value: String?): WatchStatus? = entries.firstOrNull { it.wireValue == value }
    }
}
