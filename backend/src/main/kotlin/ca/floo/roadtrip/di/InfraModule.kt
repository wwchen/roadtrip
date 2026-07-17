package ca.floo.roadtrip.di

import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.campflare.HttpCampflareAvailabilityClient
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.HttpReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.ConfigSection
import ca.floo.roadtrip.config.DbConfig
import ca.floo.roadtrip.db.dataSourceFor
import ca.floo.roadtrip.db.dsl
import ca.floo.roadtrip.db.migrate
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.routing.RouteCache
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jooq.DSLContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import javax.sql.DataSource

private const val STATIC_DIR_KEY = "static-dir"
private const val DEFAULT_STATIC_DIR = "."
private const val POI_REGISTRY_RESOURCE_KEY = "resource"
private const val POI_REGISTRY_PATH_KEY = "path"
private const val MAPBOX_TOKEN_KEY = "token"
private const val DEFAULT_POI_REGISTRY_RESOURCE = "poi-registry.yaml"
private const val RAW_DATA_DIR = "data/raw"

fun infraModule(baseConfig: ApplicationConfig) =
    module {
        single<Map<String, String>> { ApplicationProperties.load(baseConfig = baseConfig) }
        single { AppConfig.fromProperties(get<Map<String, String>>()) }

        single<DataSource> {
            val properties: Map<String, String> = get()
            val roadtripConfig = ConfigSection(properties).section("roadtrip")
            val ds = dataSourceFor(DbConfig.fromConfig(roadtripConfig.section("db")))
            migrate(ds)
            ds
        }

        single<DSLContext> { dsl(get<DataSource>()) }

        single(named("staticDir")) {
            val properties: Map<String, String> = get()
            val roadtripConfig = ConfigSection(properties).section("roadtrip")
            File(roadtripConfig.valueOrDefault(STATIC_DIR_KEY, DEFAULT_STATIC_DIR))
        }

        single<PoiRegistry> {
            val properties: Map<String, String> = get()
            val roadtripConfig = ConfigSection(properties).section("roadtrip")
            val config = roadtripConfig.section("poi-registry")
            val staticDir: File = get(named("staticDir"))
            val pathOverride = config.value(POI_REGISTRY_PATH_KEY)
            if (pathOverride != null) {
                PoiRegistry.load(staticDir.resolveConfiguredPath(pathOverride))
            } else {
                PoiRegistry.loadResource(
                    config.valueOrDefault(POI_REGISTRY_RESOURCE_KEY, DEFAULT_POI_REGISTRY_RESOURCE),
                )
            }
        }

        single<RecGovAvailabilityClient> { HttpRecgovAvailabilityClient() }
        single<AspiraAvailabilityClient> { HttpAspiraAvailabilityClient() }
        single<ReserveAmericaAvailabilityClient> { HttpReserveAmericaAvailabilityClient() }
        single<ReserveCaliforniaAvailabilityClient> { HttpReserveCaliforniaAvailabilityClient() }
        single<CampflareAvailabilityClient> {
            val config: AppConfig = get()
            HttpCampflareAvailabilityClient(
                apiBaseUrl = config.campflare.apiBaseUrl,
                apiKey = config.campflare.apiKey,
            )
        }

        single {
            val properties: Map<String, String> = get()
            val token = ConfigSection(properties).section("roadtrip").section("mapbox").value(MAPBOX_TOKEN_KEY)
            MapboxGeocoder(token = token)
        }
        single {
            val properties: Map<String, String> = get()
            val token = ConfigSection(properties).section("roadtrip").section("mapbox").value(MAPBOX_TOKEN_KEY)
            MapboxDirections(token = token)
        }

        single {
            val config: AppConfig = get()
            RouteCache(
                directions = get<MapboxDirections>(),
                ttl = config.cache.ttlFor(ApiCacheEntity.ROUTE),
                persistentCache = get<ApiCacheRepo>(),
            )
        }

        single {
            val ctx: DSLContext = get()
            sweepStaleIngestRuns(ctx)
            val staticDir: File = get(named("staticDir"))
            IngestController(
                ctx = ctx,
                etl =
                    EtlOrchestrator(
                        ctx = ctx,
                        rawDir = staticDir.resolveConfiguredPath(RAW_DATA_DIR),
                        poiRegistry = get(),
                        canonicalViews = get<CanonicalViewRepo>(),
                    ),
                importTargets = importTargetsFromRegistry(get()),
            )
        }

        single { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    }

private fun File.resolveConfiguredPath(path: String): File {
    val configured = File(path)
    return if (configured.isAbsolute) configured else File(this, path)
}
