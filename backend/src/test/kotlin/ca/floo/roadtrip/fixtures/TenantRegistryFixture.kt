package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry

/**
 * The registry the app actually ships. Display tests pin the shipped names
 * rather than a hand-built copy, so a YAML edit that changes what a user reads
 * fails here instead of in production.
 */
internal fun shippedTenantRegistry(): TenantRegistry = TenantRegistry.from(PoiRegistry.loadResource("poi-registry.yaml"))
