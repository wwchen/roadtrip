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
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/** Where Gradle tells the suite to append its ledger. Absent in an IDE run: the checks still run, nothing is written. */
internal const val CONTRACT_LEDGER_PROPERTY = "roadtrip.contractLedger"

internal const val LEDGER_EXERCISED = "EXERCISED"
internal const val LEDGER_VIOLATION = "VIOLATION"

/**
 * A violation a fixture app provoked on purpose. Its own kind, so the ledger gate
 * can fail on every [LEDGER_VIOLATION] with no exemption list — and in particular
 * with no exemption keyed off a path, which would silence the gate on a live row.
 */
internal const val LEDGER_EXPECTED_VIOLATION = "EXPECTED_VIOLATION"

internal const val LEDGER_FIELD_SEPARATOR = "\t"

/**
 * The status a drifted response is rewritten to.
 *
 * Outside the registered range and nothing any test asserts, so the drift fails
 * the test that produced it rather than waiting for the suite-level ledger gate:
 * a test that checks only `HttpStatusCode.OK` still fails, and so does one that
 * checks a 500.
 */
internal const val CONTRACT_DRIFT_STATUS = 599

private const val CONTRACT_DRIFT_REASON = "Contract body drift"

private val log = LoggerFactory.getLogger("ca.floo.roadtrip.route.ContractBodyCheck")

/**
 * `roadtripApiJson`'s decode behaviour with one setting tightened.
 *
 * Derived from the one encoder rather than restated, so a setting added there — a
 * `serializersModule`, a naming strategy — reaches the check too. `explicitNulls
 * = false` is not a loosening: it is what lets a body honestly omit a nullable
 * field, which the encoder always does. `ignoreUnknownKeys = false` is the
 * tightening — at runtime the decoder tolerates an extra key and promises nothing
 * about it, and here an extra key is exactly the drift being hunted.
 */
private val strictApiJson = Json(roadtripApiJson) { ignoreUnknownKeys = false }

/** A response body the contract cannot account for. Logged with the drift so the message carries a stack trace. */
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
 *
 * A handler that returns content replaces what the pipeline goes on to send. That
 * is how drift reaches the test: a throw here never arrives at the client, since
 * Ktor's fallback response turns it into a 500 the test may well be asserting.
 */
private object ResponseRendered : Hook<suspend (ApplicationCall, OutgoingContent) -> OutgoingContent?> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, OutgoingContent) -> OutgoingContent?,
    ) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.Render) {
            val content = subject as? OutgoingContent ?: return@intercept
            handler(context, content)?.let { proceedWith(it) }
        }
    }
}

/** How a suite installs the check. */
internal class ContractBodyCheckConfig {
    /**
     * Whether this application provokes drift deliberately. Only
     * `ContractBodyCheckTest` does: its violations are recorded as
     * [LEDGER_EXPECTED_VIOLATION] so the ledger gate never has to exempt a path.
     */
    var fixture: Boolean = false
}

/**
 * Holds every JSON body a route test produces to the [ApiContract] row for its
 * method, path and status.
 *
 * Installed by `routeTestApplication`, so a test gets it by using the harness and
 * nothing else. A row naming the wrong DTO, a DTO that drifted from what the
 * route serves, and a status neither the row nor the route's access level
 * declares each answer [CONTRACT_DRIFT_STATUS], which fails the test that
 * produced them.
 */
@Suppress("TopLevelPropertyNaming")
internal val ContractBodyCheck =
    createApplicationPlugin(name = "ContractBodyCheck", ::ContractBodyCheckConfig) {
        val fixture = pluginConfig.fixture
        // The routing root's own pipeline is merged into every route's, so this
        // runs on the RoutingPipelineCall of every matched request — the one place
        // the matched leaf is readable for a call that is about to throw.
        application.routing { }.intercept(ApplicationCallPipeline.Plugins) {
            (context as? RoutingPipelineCall)?.route?.matchedRoute()?.let { context.attributes.put(matchedRouteKey, it) }
        }
        on(ResponseRendered) { call, content ->
            if (content.contentType?.withoutParameters() != ContentType.Application.Json) return@on null
            val matched = call.matchedRoute() ?: return@on null
            ContractLedger
                .check(matched.leaf, matched.access, call.sendStatusOf(content), content, fixture)
                ?.let { TextContent(it, ContentType.Text.Plain, HttpStatusCode(CONTRACT_DRIFT_STATUS, CONTRACT_DRIFT_REASON)) }
        }
    }

/**
 * The status [content] will be sent with.
 *
 * At Render the engine has not yet copied the content's own status onto the
 * response, so `response.status()` is still null for every `respondText` and
 * every converted DTO — reading it first would check a 404 body against the row's
 * 200. The content's own status is the authority; the response is the fallback
 * for a handler that set the status separately.
 */
private fun ApplicationCall.sendStatusOf(content: OutgoingContent): Int = (content.status ?: response.status() ?: HttpStatusCode.OK).value

/** The matched route as the contract sees it: its `(verb, path template)` and the access level it declares. */
private class MatchedRoute(
    val leaf: RouteLeaf,
    val access: RouteAccess?,
)

/**
 * Where routing leaves the matched leaf for Render to find.
 *
 * `StatusPages` catches a handler's throw on the *engine* call and answers
 * there, so the response it writes reaches Render with no `route` to read and
 * every `StatusPages`-produced body would be skipped. `RoutingPipelineCall`
 * has no attributes of its own — it delegates to the engine call's — so a value
 * written while still inside routing is readable from either call object.
 */
private val matchedRouteKey = AttributeKey<MatchedRoute>("ContractBodyCheckMatchedRoute")

/**
 * The matched route, spelled the way [ApiContract] spells it. Null for a call
 * that never reached a routing node carrying a verb.
 */
private fun ApplicationCall.matchedRoute(): MatchedRoute? =
    (this as? RoutingPipelineCall)?.route?.matchedRoute() ?: attributes.getOrNull(matchedRouteKey)

/** The nearest ancestor of this node that carries a verb, as a contract leaf. */
private fun RoutingNode.matchedRoute(): MatchedRoute? {
    var node: RoutingNode? = this
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
 * written, not just the failures: the exercised set is what the ledger gate reads
 * to prove a row with a success body was actually produced, and the violations
 * are the record for a drift whose 599 nobody looked at.
 * [violationsStartingWith] is the same violation record in memory, for a test
 * that wants the message without a ledger file.
 */
internal object ContractLedger {
    private val rowsByLeaf = ApiContract.endpoints.associateBy { RouteLeaf(it.method.wireValue, it.path) }
    private val serializers = ConcurrentHashMap<KClass<*>, KSerializer<Any?>>()
    private val ledgerFile = System.getProperty(CONTRACT_LEDGER_PROPERTY)?.let(::File)
    private val exercised = ConcurrentHashMap.newKeySet<Triple<String, String, Int>>()
    private val violations = CopyOnWriteArrayList<String>()
    private val lock = Any()

    /** Every violation message this process has recorded that begins with [prefix]. */
    fun violationsStartingWith(prefix: String): List<String> = violations.filter { it.startsWith(prefix) }

    /** The drift message, or null when the body is what the contract says it is. */
    fun check(
        leaf: RouteLeaf,
        access: RouteAccess?,
        status: Int,
        content: OutgoingContent,
        fixture: Boolean,
    ): String? {
        // A response on a path no row names is not this check's business: the row
        // map is the ignore list, which is what covers /test/, /data/ and the
        // synthetic probe paths the coverage tests mount.
        val row = rowsByLeaf[leaf] ?: return null
        // The access guard refuses before any handler runs, and a row must not
        // restate what the level already declares, so its 401 and 403 count as
        // declared here exactly as the OpenAPI document publishes them.
        val declared =
            row.bodyAt(status)
                ?: access?.guardBodies()?.firstOrNull { it.status == status }
                ?: return fail(
                    leaf,
                    status,
                    fixture,
                    "the contract declares no body at $status. Add an ApiBody($status, …) to the row in " +
                        "model/api/ApiContract.kt, or stop serving that status.",
                )
        val expected =
            declared.body
                ?: return fail(leaf, status, fixture, "the contract declares $status as body-less, but the route sent JSON.")
        // Resolution 12: every JSON a route answers with arrives here as TextContent
        // — respondText makes one directly and the kotlinx converter returns one for
        // a StringFormat. Anything else on a contracted row is unreadable to the
        // check, so it is drift rather than a silent skip.
        val body =
            (content as? TextContent)?.text
                ?: return fail(
                    leaf,
                    status,
                    fixture,
                    "the route answered JSON as ${content::class.simpleName}, which the check cannot read. " +
                        "Respond with a DTO or respondEncodedJson so the body is TextContent.",
                )
        compare(leaf, status, expected, body)?.let { return fail(leaf, status, fixture, it) }
        record(LEDGER_EXERCISED, leaf, status, requireNotNull(expected.qualifiedName))
        return null
    }

    /** What is wrong with [body] as an [expected], or null when nothing is. */
    private fun compare(
        leaf: RouteLeaf,
        status: Int,
        expected: KClass<*>,
        body: String,
    ): String? {
        val serializer = serializers.getOrPut(expected) { serializer(expected.createType()) }
        val decoded =
            try {
                strictApiJson.decodeFromString(serializer, body)
            } catch (cause: Exception) {
                return "the body does not strict-decode into ${expected.qualifiedName}: ${cause.message}. " +
                    "Either the row names the wrong DTO, or the route serves a field the DTO lacks."
            }
        val sent = Json.parseToJsonElement(body)
        val roundTripped: JsonElement = roadtripApiJson.encodeToJsonElement(serializer, decoded)
        if (roundTripped == sent) return null
        return "the body is not what ${expected.qualifiedName} encodes to. sent: $sent -- re-encoded: $roundTripped"
    }

    private fun fail(
        leaf: RouteLeaf,
        status: Int,
        fixture: Boolean,
        detail: String,
    ): String {
        val message = "${leaf.method} ${leaf.path} -> $status: $detail"
        violations += message
        record(if (fixture) LEDGER_EXPECTED_VIOLATION else LEDGER_VIOLATION, leaf, status, detail)
        // The 599 says which test drifted; this says how, with the stack the
        // rewritten response cannot carry.
        log.error(message, ContractBodyMismatch(message))
        return message
    }

    private fun record(
        kind: String,
        leaf: RouteLeaf,
        status: Int,
        detail: String,
    ) {
        val file = ledgerFile ?: return
        // One line per exercised triple, not per response: the gate reads them into
        // a set, and every route test would otherwise append to one locked file at
        // its response boundary. Violations are always written.
        if (kind == LEDGER_EXERCISED && !exercised.add(Triple(leaf.method, leaf.path, status))) return
        val fields = listOf(kind, leaf.method, leaf.path, status.toString(), detail.replace('\n', ' '))
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(fields.joinToString(LEDGER_FIELD_SEPARATOR) + "\n")
        }
    }
}
