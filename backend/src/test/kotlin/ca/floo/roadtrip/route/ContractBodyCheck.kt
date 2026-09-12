package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.bodyAt
import ca.floo.roadtrip.model.api.guardBodies
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteLeaf
import ca.floo.roadtrip.route.common.reachableAccess
import ca.floo.roadtrip.route.common.roadtripApiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingPipelineCall
import io.ktor.server.routing.path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/** Where Gradle tells the suite to append its ledger. Absent in an IDE run: the checks still run, nothing is written. */
internal const val CONTRACT_LEDGER_PROPERTY = "roadtrip.contractLedger"

internal const val LEDGER_EXERCISED = "EXERCISED"
internal const val LEDGER_VIOLATION = "VIOLATION"
internal const val LEDGER_FIELD_SEPARATOR = "\t"

/**
 * `roadtripApiJson`'s decode behaviour with one setting tightened.
 *
 * `explicitNulls = false` is not a loosening: it is what lets a body honestly
 * omit a nullable field, which the one encoder always does. `ignoreUnknownKeys =
 * false` is the tightening — at runtime the decoder tolerates an extra key and
 * promises nothing about it, and here an extra key is exactly the drift being
 * hunted.
 */
private val strictApiJson =
    Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    }

/** A response body the contract cannot account for. The message names the route, the status and the difference. */
internal class ContractBodyMismatch(
    message: String,
) : AssertionError(message)

/**
 * After every Transform, before any ContentEncoding.
 *
 * The send pipeline runs Before, Transform, Render, ContentEncoding,
 * TransferEncoding, After, Engine. `onCallRespond` is Transform, where the
 * subject may still be the DTO `ContentNegotiation` is about to serialize; the
 * built-in `ResponseBodyReadyForSend` hook is After, by which point `Compression`
 * may have replaced the body. Render is the one phase that sees the final text,
 * uncompressed — and it sees it for route responses too, because
 * `RoutingRoot.executeResult` merges the application's send pipeline with the
 * route's and executes it with the routing call.
 */
private object ResponseRendered : Hook<suspend (ApplicationCall, OutgoingContent) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, OutgoingContent) -> Unit,
    ) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.Render) {
            (subject as? OutgoingContent)?.let { handler(context, it) }
        }
    }
}

/**
 * Holds every JSON body a route test produces to the [ApiContract] row for its
 * method, path and status.
 *
 * Installed by `routeTestApplication`, so a test gets it by using the harness and
 * nothing else. A row naming the wrong DTO, a DTO that drifted from what the
 * route serves, and a status no row declares each fail the test that produced
 * them.
 */
@Suppress("TopLevelPropertyNaming")
internal val ContractBodyCheck =
    createApplicationPlugin(name = "ContractBodyCheck") {
        on(ResponseRendered) { call, content ->
            val text = content as? TextContent ?: return@on
            if (text.contentType.withoutParameters() != ContentType.Application.Json) return@on
            val matched = call.matchedRoute() ?: return@on
            ContractLedger.check(matched.leaf, matched.access, text.statusValue(call), text.text)
        }
    }

/**
 * The status this body will be sent with.
 *
 * At Render the engine has not yet copied the content's own status onto the
 * response, so `call.response.status()` is still null for every `respondText`
 * and every converted DTO — reading it first would check a 404 body against the
 * row's 200. [OutgoingContent.status] is the authority; the response is the
 * fallback for a handler that set the status separately.
 */
private fun OutgoingContent.statusValue(call: ApplicationCall): Int = (status ?: call.response.status() ?: HttpStatusCode.OK).value

/** The matched route as the contract sees it: its `(verb, path template)` and the access level it declares. */
private class MatchedRoute(
    val leaf: RouteLeaf,
    val access: RouteAccess?,
)

/**
 * The matched route, spelled the way [ApiContract] spells it. Null for a call
 * that never reached a routing node carrying a verb.
 */
private fun ApplicationCall.matchedRoute(): MatchedRoute? {
    var node: RoutingNode? = (this as? RoutingPipelineCall)?.route
    while (node != null) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector) {
            val leaf = RouteLeaf(selector.method.value, node.path(OpenApiRoutePathFormat))
            return MatchedRoute(leaf, node.reachableAccess())
        }
        node = node.parent
    }
    return null
}

/**
 * The check, and the process-wide record of what the run exercised.
 *
 * Test classes run as threads in one JVM — `junit-platform.properties` sets
 * `classes.default=concurrent` and `maxParallelForks` is the default 1 — so one
 * file under one lock is enough and no per-fork name is needed. Every outcome is
 * written, not just the failures, because the throw does not reach the test's
 * client: a send-pipeline interceptor that throws is turned into a 500 by Ktor's
 * fallback response, so the test that produced the drift fails on the status it
 * asserted, and the ledger is the record that fails the build for one that
 * asserted none. [violationsStartingWith] is the same record in memory, for a
 * test that wants the message without a ledger file.
 */
internal object ContractLedger {
    private val rowsByLeaf = ApiContract.endpoints.associateBy { RouteLeaf(it.method.wireValue, it.path) }
    private val serializers = ConcurrentHashMap<KClass<*>, KSerializer<Any?>>()
    private val ledgerFile = System.getProperty(CONTRACT_LEDGER_PROPERTY)?.let(::File)
    private val violations = CopyOnWriteArrayList<String>()
    private val lock = Any()

    /** Every violation message this process has recorded that begins with [prefix]. */
    fun violationsStartingWith(prefix: String): List<String> = violations.filter { it.startsWith(prefix) }

    fun check(
        leaf: RouteLeaf,
        access: RouteAccess?,
        status: Int,
        body: String,
    ) {
        // A response on a path no row names is not this check's business: the row
        // map is the ignore list, which is what covers /test/, /data/ and the
        // synthetic probe paths the coverage tests mount.
        val row = rowsByLeaf[leaf] ?: return
        // The access guard refuses before any handler runs, and a row must not
        // restate what the level already declares, so its 401 and 403 count as
        // declared here exactly as the OpenAPI document publishes them.
        val declared =
            row.bodyAt(status)
                ?: access?.guardBodies()?.firstOrNull { it.status == status }
                ?: fail(
                    leaf,
                    status,
                    "the contract declares no body at $status. Add an ApiBody($status, …) to the row in " +
                        "model/api/ApiContract.kt, or stop serving that status.",
                )
        val expected =
            declared.body
                ?: fail(leaf, status, "the contract declares $status as body-less, but the route sent JSON.")
        compare(leaf, status, expected, body)
        record(LEDGER_EXERCISED, leaf, status, requireNotNull(expected.qualifiedName))
    }

    private fun compare(
        leaf: RouteLeaf,
        status: Int,
        expected: KClass<*>,
        body: String,
    ) {
        val serializer = serializers.getOrPut(expected) { serializer(expected.createType()) }
        val decoded =
            try {
                strictApiJson.decodeFromString(serializer, body)
            } catch (cause: Exception) {
                fail(
                    leaf,
                    status,
                    "the body does not strict-decode into ${expected.qualifiedName}: ${cause.message}. " +
                        "Either the row names the wrong DTO, or the route serves a field the DTO lacks.",
                )
            }
        val sent = Json.parseToJsonElement(body)
        val roundTripped: JsonElement = roadtripApiJson.encodeToJsonElement(serializer, decoded)
        if (roundTripped != sent) {
            fail(
                leaf,
                status,
                "the body is not what ${expected.qualifiedName} encodes to. " +
                    "sent: $sent -- re-encoded: $roundTripped",
            )
        }
    }

    private fun fail(
        leaf: RouteLeaf,
        status: Int,
        detail: String,
    ): Nothing {
        record(LEDGER_VIOLATION, leaf, status, detail)
        val message = "${leaf.method} ${leaf.path} -> $status: $detail"
        violations += message
        throw ContractBodyMismatch(message)
    }

    private fun record(
        kind: String,
        leaf: RouteLeaf,
        status: Int,
        detail: String,
    ) {
        val file = ledgerFile ?: return
        val fields = listOf(kind, leaf.method, leaf.path, status.toString(), detail.replace('\n', ' '))
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(fields.joinToString(LEDGER_FIELD_SEPARATOR) + "\n")
        }
    }
}
