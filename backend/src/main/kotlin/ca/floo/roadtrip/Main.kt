package ca.floo.roadtrip

import ca.floo.campsite.recgov.booker.campsiteModule
import ca.floo.campsite.recgov.booker.campsiteRoutes
import ca.floo.roadtrip.api.geocodeRoutes
import ca.floo.roadtrip.api.healthRoutes
import ca.floo.roadtrip.api.poiRoutes
import ca.floo.roadtrip.api.routeRoutes
import ca.floo.roadtrip.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.aspira.CachedAspiraAvailability
import ca.floo.roadtrip.aspira.aspiraAvailabilityRoutes
import ca.floo.roadtrip.etl.EtlOrchestrator
import ca.floo.roadtrip.etl.registry.PoiRegistry
import ca.floo.roadtrip.geocode.MapboxGeocoder
import ca.floo.roadtrip.importer.DbConfig
import ca.floo.roadtrip.importer.dataSourceFor
import ca.floo.roadtrip.importer.dsl
import ca.floo.roadtrip.importer.migrate
import ca.floo.roadtrip.ingest.IngestController
import ca.floo.roadtrip.ingest.adminIngestRoutes
import ca.floo.roadtrip.ingest.fetchTargetsFromRegistry
import ca.floo.roadtrip.ingest.importTargetsFromRegistry
import ca.floo.roadtrip.ingest.sweepStaleIngestRuns
import ca.floo.roadtrip.route.MapboxDirections
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val ds = dataSourceFor(DbConfig.fromEnv())
    // Run Flyway in-process so the campsite tables (V2__campsite.sql) exist
    // before campsiteModule reads them. Roadtrip's V1__pois.sql is normally
    // run by the importer's psql migrate, but baselineOnMigrate keeps this
    // safe whether the DB was hand-bootstrapped or fresh.
    migrate(ds)
    val ctx = dsl(ds)
    val campsite = campsiteModule(ctx)

    // ROADTRIP_STATIC_DIR points at the repo checkout when running locally
    // (gradle run) or at /app/static inside the container (bind-mounted from
    // the host's repo root). Raw upstream captures live under data/raw/.
    val staticDir = File(System.getenv("ROADTRIP_STATIC_DIR") ?: ".")
    val rawDataDir = File(staticDir, "data/raw")

    // Mapbox Directions for /api/route + Mapbox Geocoding for /api/geocode.
    // Both share MAPBOX_TOKEN. Token stays server-side — never sent to the
    // browser. Endpoints respond 503 if unset; the rest of the app is
    // unaffected.
    val mapboxToken = System.getenv("MAPBOX_TOKEN")
    val mapboxDirections = MapboxDirections(token = mapboxToken)
    val mapboxGeocoder = MapboxGeocoder(token = mapboxToken)
    // /api/route seeds the cache; /api/pois reads it for corridor filtering
    // so the FE doesn't have to ship a turf.buffer polygon over the wire on
    // every pan. See RouteCache.kt.
    val routeCache =
        ca.floo.roadtrip.route
            .RouteCache(mapboxDirections)

    // POI registry — config/poi-registry.yaml is the source of truth for
    // the per-data_source fetch recipes and the per-poi_data ETL chains.
    // Validates + topo-sorts the DAG at boot; refuses to start on duplicate
    // slugs, dangling inputs, forward references, or cycles.
    val registryFile = File(staticDir, "config/poi-registry.yaml")
    val poiRegistry = PoiRegistry.load(registryFile)

    // Ingestion controller (RFC 0004 / issue #44) — observability + remote
    // trigger layer around the data-fetch (Python scripts) + data-import
    // (Kotlin Importer) phases. Boot recovery first, so admins see a clean
    // dashboard.
    sweepStaleIngestRuns(ctx)
    val ingestController =
        IngestController(
            ctx = ctx,
            etl =
                EtlOrchestrator(
                    ctx,
                    File(staticDir, "data/raw"),
                    File(staticDir, "data/etl-out"),
                    poiRegistry,
                ),
            fetchTargets = fetchTargetsFromRegistry(poiRegistry, staticDir),
            importTargets = importTargetsFromRegistry(poiRegistry),
            workingDir = staticDir,
        )

    // Self-documenting /api/docs (issue #47). Builds the OpenAPI spec from
    // the live routing tree at boot; routes carry their own `documentation
    // { summary = ... }` blocks. /api/docs serves Swagger UI; /api/docs/openapi.json
    // serves the spec. Both are public — non-sensitive paths + summaries.
    install(SwaggerUI) {
        info {
            title = "roadtrip API"
            description = "Backend for roadtrip.floo.ca. Endpoints reflect the live routing tree."
        }
    }

    install(ConditionalHeaders)
    install(Compression) {
        gzip {
            // geojson is the big payoff (~5x smaller). Don't gzip below 1KB —
            // header overhead outweighs savings.
            minimumSize(1024)
            matchContentType(
                ContentType.Text.Html,
                ContentType.Text.CSS,
                ContentType.Application.Json,
                ContentType.Application.JavaScript,
                ContentType("application", "geo+json"),
                ContentType.Image.SVG,
            )
        }
    }
    install(CachingHeaders) {
        options { _, content ->
            // index.html stays no-cache so the deploy you just shipped is what
            // loads. JS/CSS also no-cache so deploys propagate instantly;
            // ConditionalHeaders + Last-Modified still let the server answer
            // 304 Not Modified, so bytes-on-the-wire are ~0 when unchanged.
            // Data/asset files get a day so the trip is offline-tolerant.
            //
            // We match on (type, subtype) strings instead of `ContentType`
            // constants because Ktor's static-file plugin returns
            // `text/javascript` for .js (the modern MIME), while the
            // `ContentType.Application.JavaScript` constant is
            // `application/javascript` — `==` would miss it and the cache
            // header would silently never apply.
            val ct = content.contentType?.withoutParameters() ?: return@options null
            val type = ct.contentType
            val subtype = ct.contentSubtype
            when {
                type == "text" && subtype == "html" ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .NoCache(null),
                    )
                // JS: text/javascript OR application/javascript. CSS: text/css.
                (type == "text" || type == "application") && subtype == "javascript" ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .NoCache(null),
                    )
                type == "text" && subtype == "css" ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .NoCache(null),
                    )
                type == "application" && (subtype == "json" || subtype == "geo+json") ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .MaxAge(86400),
                    )
                type == "image" ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .MaxAge(86400),
                    )
                else -> null
            }
        }
    }

    // Aspira NextGen availability (Parks Canada / WA State / BC Discover Camping).
    // Same pattern as rec.gov's CachedAvailability — process-wide singleton with
    // a 10-min TTL and a 1.5s mutex against Aspira's Azure WAF. See
    // ca/floo/roadtrip/aspira/AspiraAvailabilityClient.kt.
    val aspiraCache = CachedAspiraAvailability(AspiraAvailabilityClient())

    routing {
        // /api/docs — Swagger UI; /api/docs/openapi.json — the spec it loads.
        // Both must be mounted before the static file fallthrough at "/" so
        // the catch-all doesn't shadow them.
        route("/api/docs") {
            swaggerUI("/api/docs/openapi.json")
        }
        route("/api/docs/openapi.json") {
            openApiSpec()
        }

        poiRoutes(ctx, routeCache, poiRegistry)
        routeRoutes(routeCache)
        geocodeRoutes(mapboxGeocoder)
        healthRoutes(rawDataDir)
        aspiraAvailabilityRoutes(aspiraCache)
        adminIngestRoutes(ingestController, ctx)
        campsiteRoutes(campsite)
        // Static site. /web/* and /data/* serve directly from the repo
        // checkout. Root path serves index.html. data/raw/ stays
        // server-private — it's the upstream capture cache, never served.
        staticFiles("/web", File(staticDir, "web"))
        staticFiles("/data", File(staticDir, "data")) {
            exclude { it.path.contains("/raw/") }
            contentType { f ->
                if (f.name.endsWith(".geojson")) ContentType("application", "geo+json") else null
            }
        }
        // Campsite UI served from the JAR's classpath
        // (backend/src/main/resources/static/campsite/), separate from
        // roadtrip's repo-checkout static files. index.html serves at
        // /campsite/.
        staticResources("/campsite", "static/campsite") {
            default("index.html")
        }
        staticFiles("/", staticDir) {
            default("index.html")
            // Only serve top-level files (index.html, favicon.ico, etc.) from
            // the root mount — /web and /data have their own routes above,
            // and we don't want to expose backend/, scripts/, etc.
            exclude { f ->
                val rel = f.relativeTo(staticDir).path
                rel.contains(File.separator) // disallow nested paths
            }
        }
    }
}
