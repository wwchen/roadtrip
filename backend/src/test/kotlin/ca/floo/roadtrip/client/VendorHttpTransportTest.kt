package ca.floo.roadtrip.client

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val TEST_TIMEOUT_MS = 50L
private val testUri: URI = URI.create("https://vendor.test/grid")

private class VendorFailure(
    message: String,
) : Exception(message)

private suspend fun sendThrough(client: HttpClient): String =
    VendorHttpTransport.send(client, HttpRequest.newBuilder(testUri).build(), "vendor", testUri.toString()) { message, _, _ ->
        VendorFailure(message)
    }

class VendorHttpTransportTest {
    @Test
    fun `a caller's timeout cancels the request rather than reading as the vendor failing`() {
        runBlocking {
            val answered = withTimeoutOrNull(TEST_TIMEOUT_MS) { sendThrough(ScriptedHttpClient { CompletableFuture() }) }

            assertNull(answered)
        }
    }

    @Test
    fun `a transport failure is the vendor's`() {
        runBlocking {
            val failure =
                assertFailsWith<VendorFailure> { sendThrough(ScriptedHttpClient { CompletableFuture.failedFuture(IOException("reset")) }) }

            assertEquals(true, failure.message?.contains("vendor request failed"))
        }
    }
}
