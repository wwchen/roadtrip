package ca.floo.roadtrip

import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderClients
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.routing.RouteCache
import org.jooq.DSLContext
import java.io.File
import javax.sql.DataSource

internal data class RoadtripBootContext(
    val properties: Map<String, String>,
    val appConfig: AppConfig,
    val dataSource: DataSource,
    val ctx: DSLContext,
    val availabilityProviderClients: AvailabilityProviderClients,
    val staticDir: File,
    val mapboxGeocoder: MapboxGeocoder,
    val routeCache: RouteCache,
    val poiRegistry: PoiRegistry,
    val ingestController: IngestController,
)
