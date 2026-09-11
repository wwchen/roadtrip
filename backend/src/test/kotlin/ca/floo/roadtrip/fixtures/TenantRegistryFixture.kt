package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry

/** What the shipped registry calls the rec.gov ref most booking fixtures resolve to. */
internal const val RECGOV_DISPLAY_NAME = "Recreation.gov"

/** The shipped name for the vendor that serves but does not sell. */
internal const val CAMPFLARE_DISPLAY_NAME = "Campflare"

/** Parsed and validated once: the shipped resource cannot change within a JVM. */
private val shipped: TenantRegistry by lazy { TenantRegistry.from(PoiRegistry.loadResource("poi-registry.yaml")) }

/**
 * The registry the app actually ships. Display tests pin the shipped names
 * rather than a hand-built copy, so a YAML edit that changes what a user reads
 * fails here instead of in production.
 */
internal fun shippedTenantRegistry(): TenantRegistry = shipped
