package ca.floo.roadtrip.models

/**
 * The kind of bookable thing a [ReservableId] points at. RFC 0008 ships
 * `Site` only; `Permit` and `Ticket` are reserved for future RFCs once we
 * have real upstream data to design their response shapes against.
 *
 * The wire form is the lowercase enum name (`"site"`).
 */
enum class ReservableType {
    /** A campsite or sub-area resource — a single bookable unit at a campground. */
    SITE,

    ;

    fun encode(): String = name.lowercase()

    companion object {
        fun parse(raw: String): ReservableType? = entries.firstOrNull { it.encode() == raw.lowercase() }
    }
}
