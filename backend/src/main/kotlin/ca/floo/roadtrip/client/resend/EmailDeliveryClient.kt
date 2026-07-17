package ca.floo.roadtrip.client.resend

data class EmailDeliveryMessage(
    val from: String,
    val to: String,
    val subject: String,
    val text: String,
    val html: String,
)

interface EmailDeliveryClient {
    suspend fun send(message: EmailDeliveryMessage): Boolean
}
