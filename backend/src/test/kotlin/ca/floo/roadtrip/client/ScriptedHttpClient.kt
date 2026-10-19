package ca.floo.roadtrip.client

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/** A client whose every request answers with [answer]'s future — never, unless the test completes it. */
internal class ScriptedHttpClient(
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
