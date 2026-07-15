package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecGovRecaccountSchema(
    @SerialName("access_token")
    val accessToken: String = "",
    val expiration: String = "",
    val account: RecGovAccountSchema = RecGovAccountSchema(),
    @SerialName("is_guest")
    val isGuest: Boolean = false,
    @SerialName("refresh_id")
    val refreshId: String = "",
)

@Serializable
data class RecGovAccountSchema(
    @SerialName("account_id")
    val accountId: String = "",
    val email: String = "",
    @SerialName("first_name")
    val firstName: String = "",
    @SerialName("last_name")
    val lastName: String = "",
)
