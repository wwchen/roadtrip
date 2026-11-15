package ca.floo.roadtrip.client.resend

data class EmailDeliveryMessage(
    val from: String,
    val to: String,
    val subject: String,
    val text: String,
    val html: String,
)

sealed interface EmailDelivery {
    data class Sent(
        val id: String,
    ) : EmailDelivery

    data class Failed(
        val reason: String,
    ) : EmailDelivery
}

interface EmailDeliveryClient {
    suspend fun send(message: EmailDeliveryMessage): EmailDelivery
}
