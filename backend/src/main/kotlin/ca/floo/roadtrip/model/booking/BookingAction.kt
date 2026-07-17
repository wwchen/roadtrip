package ca.floo.roadtrip.model.booking

enum class BookingAction {
    ADD_TO_CART,
    ;

    val wireValue: String
        get() =
            when (this) {
                ADD_TO_CART -> "add_to_cart"
            }
}
