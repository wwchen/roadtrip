package ca.floo.roadtrip.client

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val TEST_TIMEOUT_MS = 50L
private val testUri: URI = URI.create("https://vendor.test/grid")

private class VendorFailure(
    message: String,
) : Exception(message)

/** A client whose every request answers with [answer]'s future — never, unless the test completes it. */
private class ScriptedHttpClient(
    private val answer: () -> CompletableFuture<HttpResponse<String>>,
) : HttpClient() {
    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T> send(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = throw UnsupportedOperationException("the transport sends asynchronously")

    @Suppress("UNCHECKED_CAST")
    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = answer() as CompletableFuture<HttpResponse<T>>

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        pushHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)
}

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
