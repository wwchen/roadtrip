package ca.floo.roadtrip.client

import kotlinx.coroutines.future.await
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val successStatus = 200..299

/**
 * The send-and-classify half of a JDK-`HttpClient` vendor client.
 *
 * Every vendor builds its own request — headers, method and body are where they
 * genuinely differ — but what happens next was copied four times: await the
 * response, wrap any transport failure in the vendor's exception, reject a
 * non-2xx, hand back the body. Sharing it keeps one definition of "success" and
 * one shape of failure message.
 *
 * Adapters whose send does more than this keep their own: the Aspira client
 * records a throttle timestamp in a `finally` and sniffs WAF challenge pages,
 * and those are the reason it exists.
 */
internal object VendorHttpTransport {
    /**
     * A client on the shared timeouts. [configure] is for the rare per-vendor
     * need — ReserveAmerica wants a cookie jar to hold its session.
     */
    fun client(configure: HttpClient.Builder.() -> Unit = {}): HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(VendorHttpDefaults.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .apply(configure)
            .build()

    /**
     * Sends [request] and returns the body, or throws whatever [fail] builds.
     *
     * [fail] takes (message, httpStatus, cause) so each vendor keeps its own
     * exception type; [vendor] and [url] only shape the message.
     */
    suspend fun send(
        httpClient: HttpClient,
        request: HttpRequest,
        vendor: String,
        url: String,
        fail: (String, Int?, Throwable?) -> Exception,
    ): String {
        val response =
            try {
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                throw fail("$vendor request failed: ${e.javaClass.name}: ${e.message}", null, e)
            }
        if (response.statusCode() !in successStatus) {
            throw fail("$vendor HTTP ${response.statusCode()} for $url", response.statusCode(), null)
        }
        return response.body()
    }
}
