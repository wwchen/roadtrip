# Architecture audit: patchwork, layer leakage, and coupling

Date: 2026-09-09. Scope: whole repo (ETL, backend, frontend). Method: four
parallel investigations (backend layering, provider abstraction, ETL leakage,
frontend/backend seam), with the top claims re-verified against source.

**Verdict.** The layering doc is excellent and the routes honor it. The rot is
not in routes. It sits in three places: a raw vendor JSON blob
(`source_payload`) that the serving path, the CTA logic, and the browser all
quietly depend on; a vendor list that is re-enumerated in nine files despite
the doc's "one registry row" promise; and a frontend that has become the
second half of the ETL and the real owner of availability truth.

## Where the contract holds

- Routes import zero repos, zero jOOQ, and never parse provider refs.
- Repos import no Ktor, no clients, no API models.
- Vendor ETLs receive no DSLContext, repo, or HTTP client. The orchestrator dispatches on slug only.
- The Python fetch scripts are capture-only. There is no hidden pre-ETL stage.
- Capability flags are read as flags everywhere. No `if (provider == X)` stands in for one.

## Findings, ranked

### 1. Two booking-ref encodings coexist, and the serving path runs on the legacy one

V44 and V45 moved refs to typed `booking_provider(_ref)` columns. But
`repo/PoiServingRepo.kt:72` aliases `source_payload` as `provider_ref` and
rebuilds identity with a vendor-keyed SQL `CASE` over `recgov_id`,
`transactionLocationId`, `park_id`, `facility_id`. `repo/CampgroundRepo.kt:84`
digs `booking_cta_provider_ref` out of the same blob.
`model/domain/provider/BookingProviderRefLegacyJson.kt` decodes by probing keys
in order with no type tag, and its KDoc says "do not clean it up."
`service/poi/campground/CampgroundCta.kt:54` consumes it, and `provider_ref`
is still a raw `JsonElement` on the public POI DTO. In fact
`CampgroundRepo.bookingProviderRefJson` encodes the typed column *into* the
legacy JSON only so `CampgroundCta` can decode it back.

Ripple: a sixth vendor is a SQL `CASE` arm in a repo, a writer arm and a
reader arm in the legacy codec, and a key-collision risk where a shared field
name silently resolves to the wrong vendor. The ETL also has an undocumented
side channel: any key it drops into `source_payload` becomes serving behavior.

Refactor:

```kotlin
data class CampgroundPoiDetail(
    val campground: Campground,
    val bookingRef: BookingProviderRef?,        // parsed once, here
    val ctaOverrideRef: BookingProviderRef?,    // from a real column: cta_booking_provider_ref
)
// SQL poi_key: no vendor branching
// COALESCE(cg.booking_provider || ':' || cg.booking_provider_ref, cg.data_provider || ':' || cg.data_provider_ref)
// model: delete BookingProviderRefLegacyJson; API carries one shape
@Serializable data class BookingRefDto(val provider: String, val ref: String)
```

### 2. The campsite table row is the API DTO, so the browser is the ETL's second stage

`model/api/PoiCampsitesResponseSchema.kt` returns `List<Campsite>`, and
`Campsite` is `@Serializable` with `source_payload`, `created_at`,
`deleted_at`. The frontend type in `frontend/src/api/campsite-api.ts:11` is
deliberately open with an index signature. The consequence is
`frontend/src/features/availability/site-detail-facts.ts`, whose header admits
"the four providers disagree about the spelling of every field," then
reconciles four capacity spellings, eight attribute-name spellings, strips HTML
with a regex, and walks the raw payload six levels deep looking for a photo.
`frontend/src/domain/poi/campground-detail.ts` owns the amenity vocabulary,
title-cases vendor keys, parses "early May – late Sept" with a fuzzy month
parser, and infers a parent park from link-title regexes.
`frontend/src/lib/poi.ts:112` calls `JSON.parse` because JSONB columns arrive
"sometimes objects, sometimes strings." Three readers of cell-signal data
assume three different shapes, and the ETL writes a fourth.

Ripple: every new vendor ships as a Vite build, and there is no test that
fails when an ETL renames a key.

Refactor:

```kotlin
data class CampsiteUpsertCandidate(
    ..., val capacity: IntRange?, val features: List<CampsiteFeature>,
    val photoUrl: String?, val season: SeasonWindow?,          // parsed here, once
)
enum class CampsiteFeature(val wire: String, val label: String) { FIREPIT("firepit", "Fire pit"), ... }

@Serializable data class CampsiteDto(
    val id: Long, val name: String, val kind: CampsiteKind, val loop: String?,
    val capacity: CapacityDto?, val features: List<String>, val photoUrl: String?,
)   // no source_payload, no timestamps, no index signature on the TS side
```

### 3. Vendor shape reconciliation at read time in the backend too

`service/poi/CampgroundService.kt:28` carries a comment explaining that
rec.gov writes `url` and `contact.phone` while Campflare writes `large_url`
and `primary_phone`, then tries every vendor's key on each request. Email,
elevation, and last-verified are mined from JSONB with `.trim()` and
`.toDoubleOrNull()` per call. The `Campground` row has twelve `JsonElement`
fields, so "clean deterministic data" is really twelve opaque blobs. Same fix
as finding 2: the ETL candidate carries `CampgroundContact` and
`List<CampgroundPhoto>`, the repo writes columns, and the service reads fields.

### 4. The vendor set is enumerated nine times outside the adapter registry

The doc says a new provider is "a typed ref plus a provider class plus one
registry row." In practice it is also: `BookingProviderRef.parse` and
`DataProviderRef.parse` `when` blocks; the hardcoded id set in
`config/ReadPathProviderConfig.kt:14`; the six-arm if-cascade at
`di/ServiceModule.kt:473` plus a Campflare-only escape hatch at line 441 and
`removePrefix("aspira_")` at line 178; five per-vendor accessors in
`PoiRegistry` filtering on ETL class-name strings; a string `when` over class
names in `ProductionTerminalEtlRegistry`; a five-arm TTL `when` at
`service/availability/CampsiteAvailabilityService.kt:99` where every arm
returns the same two hours; a second per-vendor provider list inside
`CampgroundCta`; and five `ApiCacheEntity` enum rows.

Ripple: the config allowlist is not derived from the enum, so adding a vendor
to the enum but not the set fails boot with "unknown provider" the moment
someone enables it.

Refactor:

```kotlin
interface BookingProviderDescriptor {
    val id: BookingProvider
    val refCodec: RefCodec<BookingProviderRef>
    val snapshotTtl: Duration
    val cta: CampgroundCtaProvider
    fun etlFactories(): Map<String, (EtlEntry) -> TerminalEtlDefinition<*, *>>
}
object ProviderCatalog {
    val all: List<BookingProviderDescriptor> = listOf(RecGovDescriptor, AspiraDescriptor, ...)
    fun byId(id: BookingProvider) = all.first { it.id == id }
}
// ReadPathProviderConfig validates against BookingProvider.entries.map { it.id }, not a literal set
```

### 5. Aspira wire fields sit on the provider-neutral batch and the public API

`model/availability/AvailabilityObservationBatch.kt:12` has `campgroundId`,
`host`, `mapId`. They flow to `AvailabilityResponseDto` as `map_id`. The
proof they are vendor-shaped:
`service/availability/provider/ReserveAmericaAvailabilityProvider.kt:158`
stuffs its `contractCode` into `mapId` because there is nowhere else to put
it. The mapper's own comment says the fields exist "so the FE can pick
provider-specific CTAs."

Refactor:

```kotlin
data class AvailabilityObservationBatch(
    val scope: BookingProviderRef,     // typed; adapters fill their own variant
    val startDate: LocalDate, val endDate: LocalDate,
    val observations: List<AvailabilityObservation>, val cacheBlock: AvailabilityCacheBlock,
)
@Serializable data class AvailabilityResponseDto(
    val provider: String, @SerialName("scope_ref") val scopeRef: String, ...
)
```

### 6. The booking path is rec.gov-only wearing generic names

`service/booking/BookingActionService.kt:183` returns the hardcoded
recreation.gov cart URL for every successful hold.
`service/availability/WatchCapabilityService.kt:83` gates `atc` for any
adapter on `recgovCredentials`. `UserSettingsRepo` has `recgov_username` and
`recgov_password_cipher` columns. `AtcTriggerActionHandler` emits
`roadtrip.recgov.atc` metrics on a vendor-neutral path. `BookingActionCodes`
mixes `recgov_no_reserve_button` into the neutral code set.

Ripple: an Aspira booking adapter would be advertised as unsupported to a
user with valid Aspira credentials, would send them to the rec.gov cart on
success, and would be counted under rec.gov dashboards.

Refactor:

```kotlin
interface BookingAdapter {
    val provider: BookingProvider
    fun canFulfil(user: UserId): Boolean
    suspend fun addToCart(target: BookingTarget, user: UserId): AddToCartOutcome  // Held(cartUrl) from the adapter
}
// schema: user_booking_credentials(user_id, provider, username, secret_cipher)
// metrics: atcFired(provider = result.providerId, outcome)
```

### 7. The Campflare ETL writes rec.gov booking rows

`service/etl/vendors/campflare/CampflareCampgroundsEtl.kt:44` scrapes a
rec.gov ref out of the Campflare payload and stamps `bookingProvider =
RECGOV`. The doc says "nothing merges across vendors at write time," and it
already has a failover mechanism for exactly this case: candidate ordering in
`DbAvailabilityTargetResolver` lets a Campflare row fall through to a linked
rec.gov alias. This is a second, hidden mechanism doing the same job in a
vendor adapter.

Refactor:

```kotlin
bookingProvider = BookingProvider.CAMPFLARE, bookingProviderRef = campgroundId,
bookingAliases = listOfNotNull(recgovRef?.let { BookingProviderRef.RecGov(it) }),
// repo: campground_booking_alias(campground_id, provider, ref); RefLinkRepo returns primary + aliases
```

### 8. Availability truth is decided in the browser

`frontend/src/features/availability/fuse.ts:68` owns the campground-level
rollup, including the liability decision that `unknown` outranks `reserved`.
`frontend/src/features/availability/matrix-rows.ts:214` overrides a backend
`available` to `reserved` by inferring from the presence of an id list. Line
44 decides which cells are watchable using hyphenated CSS kind strings.
`frontend/src/lib/watch-windows.ts:157` infers "no credentials" by
subtracting two arrays, when `WatchCapabilityService.canFulfilAddToCart`
computes that exact boolean and discards it.
`frontend/src/features/availability/useWatches.ts:203` sends a literal
cadence of 60 seconds on every watch, which means the three-rung resolver in
`ResolveCadence.kt` never reaches the POI override or the configured global
default. `CALENDAR_MAX_DAYS_OUT = 365` guesses at every provider's horizon.

Refactor:

```kotlin
@Serializable data class AvailabilityDayDto(
    val date: String, val status: AvailabilityStatus,            // campground rollup, backend-owned
    val cells: Map<Long, CellDto>,
)
@Serializable data class CellDto(val status: AvailabilityStatus, val watchable: Boolean)
@Serializable data class AddToCartCapabilityDto(val state: String)  // ready | no_credentials | signed_out | unsupported
// watch create: cadence_sec omitted -> NULL -> resolver rungs apply; horizon from capabilities.bookingHorizonDays
```

### 9. Tenants, time zones, and vendor display copy are Kotlin literals

`service/availability/provider/AspiraTenants.kt` hardcodes three hosts with
`ctaLabel` and `bookingSystemLabel`, which is UI copy in the provider layer.
ReserveAmerica tenants are hardcoded in the adapter companion while the YAML
`ReserveAmericaSourceConfig` is parsed and then used only for an
`isNotEmpty()` check. `BcParksCampsite` defaults `tenant = "bc"`. V45
hardcodes `aspira_bc`, `aspira_wa`, `aspira_pc`.
`service/poi/campground/CampgroundCta.kt:172` anchors every Aspira tenant to
`America/New_York` under a TODO, while two of three tenants are Pacific. The
frontend keeps its own `AGENCY_BY_HOST` table while the backend already
serves `booking_system`, so the same drawer says "Recreation.gov" and
"rec.gov."

Refactor: tenants become rows in `poi-registry.yaml` (provider, code, host,
time_zone, horizon_days, display_name); adapters take an injected
`TenantRegistry`; the frontend drops its label tables and renders
`booking_system`.

### 10. `DSLContext` is a structural parameter of services and the ETL framework

`service/availability/AvailabilityWatchService.kt:32`,
`UserProvisioningService`, `PollerBackfill`, `EtlOrchestrator`, and
`IngestController` all hold a `DSLContext` and construct repos per
transaction. `service/etl/framework/TerminalEtlBinding.kt:49` types its sink
factory as `(DSLContext) -> TerminalSink`. `IngestController.runImport`
returns `org.jooq.JSONB`. `TransactionalWatchAlertScope` takes jOOQ despite a
KDoc claiming the port hides it.

Refactor:

```kotlin
interface UnitOfWork { fun <T> run(block: (Repos) -> T): T }
class Repos internal constructor(txn: DSLContext) {
    val watches by lazy { AvailabilityWatchRepo(txn) }; val pollers by lazy { AvailabilityPollerRepo(txn) }
}
```

### 11. Geometry matching in the Aspira ETL is a heuristic with a slug-sniffed switch

`service/etl/vendors/aspira/AspiraCampgroundsEtl.kt:365` picks the geometry
source by `slug.contains("uscampgrounds")`, and line 203 assigns coordinates
by 0.5 Jaccard fuzzy match. The `state_filter` fix threads through to one of
four sources.

Refactor: geometry source and match policy declared in YAML per ETL; the
candidate records `matchKind` so fuzzy pins are reviewable.

### 12. Vocabularies are free strings mapped ad hoc

Campsite `kind` is whatever each vendor sent, then used as a filter key end
to end. Amenity keys have no registry except a TS `Map`. Carrier slugs are
half-mapped in the ETL and half-mapped back in the frontend.
`route/api/admin/AdminIngestRoutes.kt:116` matches `"completed"`, `"noop"`
literals and 500s anything new. `ReserveAmericaCampgroundsEtl` re-derives the
enum with `settings.provider.lowercase() == "reserveamerica"`.
`RecGovAvailabilityProvider` maps unknown upstream status strings to
`RESERVED` with a silent `else`.

Refactor: `CampsiteKind`, `Amenity`, `RunStatus` enums; unknown upstream
status maps to `UNKNOWN`, never `RESERVED`.

### 13. Routes doing use-case work, and route-level constants

`RouteRoutes` chains two services and assembles the composite GeoJSON.
`GeocodeRoutes` takes a `MapboxGeocoder` client directly.
`route/api/pois/CampsiteRoutes.kt:32` hardcodes a 30 per minute rate limit
while the sibling bulk route reads the same policy from config. `AuthRoutes`
holds `setOf("google-oauth2")`. `mapAspiraUpstreamError` in the provider
layer returns Ktor `HttpStatusCode` and has no production caller.

### 14. The API contract is hand-mirrored with no codegen

Every TS type is annotated "Mirrors XDto." `longest_run_nights` exists on
the Kotlin DTO and not in TypeScript. `watch-triggers.ts` accepts both
`trigger_config` and `triggerConfig` via casts. `alert-rows.ts:92` guesses why
a watch ended from a date comparison because the payload has no retirement
reason.

## Ripple map

| Tomorrow's change | Files forced to change today |
|---|---|
| Sixth booking vendor | 2 ref codecs, config allowlist, DI cascade, PoiRegistry, ETL registry, TTL `when`, `ApiCacheEntity`, `CampgroundCta` list, `PoiServingRepo` SQL, legacy JSON codec, two frontend label tables, `site-detail-facts` spellings |
| Second booking adapter (Aspira ATC) | `BookingActionService` cart URL, `WatchCapabilityService` credential gate, `user_settings` columns, metrics names, error codes, frontend `cartGate` copy |
| Fourth Aspira tenant in Mountain time | `AspiraTenants` row, `aspiraAnchorTimeZone` is wrong for it, V45-style migration, frontend `AGENCY_BY_HOST` |
| ETL renames a payload key | Nothing fails. Frontend chips vanish, photos disappear, CTAs fall back to name search |
| New watch terminal state | `AdminIngestRoutes` returns 500, `doneKind` mislabels the alert |

## Fix order

1. Findings 1, 4, 5, and the status-string parts of 12: the typed provider seam.
2. Findings 2, 3, and the vocabulary parts of 12: ETL promotion and a closed campsite DTO.
3. Finding 8: availability truth server-side.
4. Findings 6, 7, 9: booking port, alias links, tenant registry.
5. Findings 10, 11, 13, 14: unit of work, geometry policy, route cleanup, client codegen.
