package ca.floo.roadtrip

import ca.floo.campsite.recgov.booker.campsiteModule
import ca.floo.campsite.recgov.booker.campsiteRoutes
import ca.floo.roadtrip.api.healthRoutes
import ca.floo.roadtrip.api.poiRoutes
import ca.floo.roadtrip.api.pricingRoutes
import ca.floo.roadtrip.importer.DbConfig
import ca.floo.roadtrip.importer.dataSourceFor
import ca.floo.roadtrip.importer.dsl
import ca.floo.roadtrip.importer.migrate
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
    // the host's repo root). data/pricing-cache lives under data/.
    val staticDir = File(System.getenv("ROADTRIP_STATIC_DIR") ?: ".")
    val pricingCache = File(staticDir, "data/pricing-cache")

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
            // loads. Data/asset files get a day so the trip is offline-tolerant
            // once primed; ConditionalHeaders still revalidates via Last-Modified.
            when (content.contentType?.withoutParameters()) {
                ContentType.Text.Html ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .NoCache(null),
                    )
                ContentType("application", "geo+json"),
                ContentType.Application.Json,
                ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .MaxAge(86400),
                    )
                ContentType.Application.JavaScript,
                ContentType.Text.CSS,
                ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .MaxAge(3600),
                    )
                ContentType.Image.SVG, ContentType.Image.PNG ->
                    CachingOptions(
                        io.ktor.http.CacheControl
                            .MaxAge(86400),
                    )
                else -> null
            }
        }
    }

    routing {
        poiRoutes(ctx)
        pricingRoutes(pricingCache)
        healthRoutes(pricingCache)
        campsiteRoutes(campsite)
        // Static site. /web/* and /data/* (excluding pricing-cache, which is
        // server-private) serve directly from the repo checkout. Root path
        // serves index.html.
        staticFiles("/web", File(staticDir, "web"))
        staticFiles("/data", File(staticDir, "data")) {
            // Don't expose the on-disk cache files via /data/pricing-cache/*
            // — they're served through /api/pricing/{slug} so the response
            // shape (with _cache hint) stays consistent.
            exclude { it.path.contains("/pricing-cache/") }
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
