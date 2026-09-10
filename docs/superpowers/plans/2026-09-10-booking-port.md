# Booking Port and Alias Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Campflare rows stop wearing rec.gov's identity as their primary booking ref (aliases carry it), every provider claims inventory through one rule, and the booking path is a port whose rec.gov-ness lives inside the rec.gov adapter: cart URL, credentials, codes, metrics, copy.

**Architecture:** A typed `booking_aliases` JSONB column on campgrounds and campsites (the typed-JSONB rule, `CatalogColumnJson`); `AvailabilityProvider` defaults that read primary-or-alias; the booking target resolver offering primary and aliases to the adapter registry; `BookingAdapter` grows `canFulfil` and returns its own `Held(cartUrl, provider)`; credentials move to `user_booking_credentials(user_id, provider, …)`; metrics get a `provider` attribute.

**Tech Stack:** Kotlin 2 / Ktor / jOOQ raw SQL / kotlinx.serialization / Flyway + Postgres (Testcontainers); React + TypeScript + Vitest; Grafana JSON.

**Spec:** `docs/superpowers/specs/2026-09-10-booking-port-design.md`. Audit findings 6 and 7 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issues #735, #736.

## Global Constraints

- Layering per `docs/backend-architecture.md`: SQL only in `repo/` and migrations; no Ktor in `service/`; models depend on stdlib + serialization; ETLs never touch repos; adapters do not surface vendor types through the port.
- No inline magic constants; comments short and rare; never edit an applied migration (`V58` is the latest; this plan adds `V59` and `V60`).
- Migrations canonicalize existing columns and copy existing rows; they never backfill from `source_payload`. `make data-import` after deploy.
- Wire: the POI detail's `booking_ref`, `booking_system`, `cta`, `availability_supported` follow the serving provider; `CampsiteDto` unchanged; settings routes unchanged.
- Provider preference is the DI list order (rec.gov before Campflare); `parent_ref` keys for live pollers must not change (a test pins the rec.gov key on a Campflare-primary row).
- Backend gate: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q` (Docker running); frontend gate: `cd frontend && npm run typecheck && npm test && npm run lint`.
- One commit per task, conventional prefix, `Refs #735` or `Refs #736`, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

---

### Task 1: `BookingAlias` column, V59, repos, provider defaults (#736)

**Files:**
- Create: `model/domain/provider/BookingAlias.kt` (`@Serializable data class BookingAlias(val provider: BookingProvider, val ref: String)`; `BookingProvider` serializes by its `id`), `backend/src/main/resources/db/migration/V59__booking_aliases.sql` (per the spec: columns, array CHECK, GIN index on both tables, the Campflare-primary canonicalization; `jsonb_exists`-style guards, no literal `?`).
- Modify: `model/domain/Campground.kt`, `Campsite.kt`, `CampgroundUpsertCandidate.kt`, `CampsiteUpsertCandidate.kt` (`bookingAliases: List<BookingAlias> = emptyList()`), `repo/CampgroundRepo.kt`, `repo/CampsiteRepo.kt` (column in select/insert/upsert, `CatalogColumnJson` encode/decode), `repo/RefLinkRepo.kt` (ref lookups match primary or alias via `booking_aliases @>`), `service/availability/provider/AvailabilityProvider.kt` (`supportsCampground` default primary-or-alias; `parentRefFor` primary-or-alias parsed as this provider's ref; `vendorSiteIdFor` primary-or-alias-or-data-ref), `CampflareAvailabilityProvider.kt` (delete the `dataProviderRef` overrides of `supportsCampground`/`parentRefFor`; keep `vendorSiteIdFor` only if it differs from the default), `RecGovAvailabilityProvider.kt` (`vendorSiteIdFor` via the default).
- Test: `repo/CatalogEntityRepoTest` (alias round-trip on both tables; V59 replay: a Campflare row with `booking_provider = recgov` becomes Campflare primary with one rec.gov alias, a Campflare-only row untouched, a rec.gov row untouched, idempotent), `repo/CanonicalCatalogSchemaTest` (columns), `RefLinkRepoTest` (lookup by alias), `service/availability/provider/*ProviderTest` and `DbAvailabilityTargetResolverTest` (rec.gov claims a Campflare-primary row through the alias, `parentRefKey == "232447"` unchanged; Campflare claims it too; with rec.gov disabled Campflare serves; with no alias only Campflare claims; `vendorSiteIdFor` on an aliased campsite returns the rec.gov site id).

**Interfaces:** `BookingAlias`; `Campground.bookingAliases`, `Campsite.bookingAliases`; `AvailabilityProvider.claimedRef(campground): BookingProviderRef?` (the primary-or-alias helper the three defaults share).

- [ ] Steps: failing repo/provider tests → model → V59 → repos → provider defaults → gate → commit `feat(catalog): booking aliases; providers claim inventory by primary or alias`.

---

### Task 2: Campflare ETLs emit aliases; booking target and CTA follow the serving provider (#736)

**Files:**
- Modify: `service/etl/vendors/campflare/CampflareCampgroundsEtl.kt` and `CampflareCampsitesEtl.kt` (`bookingProvider = CAMPFLARE`, `bookingProviderRef = <campflare id>`, `bookingAliases` from the rec.gov ref; `CampflareEtlSupport` extractors unchanged), `service/availability/AvailabilityBookingTargetResolver.kt` (offer the primary then each alias to the registry, first claim wins; delete the "sound only while rec.gov is the only adapter" doc), `repo/CampgroundRepo.kt` `findPoiDetail` / `service/poi/CampgroundService.kt` (the detail's `bookingRef` is the serving provider's claimed ref via `BookingHorizonResolver`; `availabilitySupported` follows), `service/poi/campground/CampgroundCta.kt` (Campflare branch: `bookingSystem = "Campflare"`, info CTA; keep the existing Campflare info CTA for aliased rows served by rec.gov), `service/availability/WatchAlertDispatcher.kt` (notification link via the serving provider's ref; verify the existing `tgt.parentRef` path already does).
- Test: `CampflareCampgroundsEtlTest`/`CampflareCampsitesEtlTest` (primary Campflare, alias rec.gov, none when no ref; the `reservation_url` fallback branch gets a case), `AvailabilityBookingTargetResolverTest` (an aliased campsite yields the rec.gov target, POI 8149's shape; a Campflare-only campsite yields no target), `CampgroundCtaTest` (Campflare-only row: `booking_system = "Campflare"`; aliased row served by rec.gov: unchanged rec.gov CTA), `PoiServiceTest`/`FeatureCollectionContractTest` (detail `booking_ref` for an aliased campground is rec.gov's).

- [ ] Steps: failing ETL + resolver tests → ETLs → resolver → detail/CTA → gate → commit `refactor(booking): Campflare rows own their identity; targets and CTAs follow the serving provider`.

---

### Task 3: The booking port owns its vendor (#735)

**Files:**
- Modify: `service/booking/BookingAdapter.kt` (`canFulfil(user: UserId): Boolean`, `addToCart` returning `AddToCartOutcome` with `Held(cartUrl, provider)`, `failureCategories` on the adapter), `RecGovBookingAdapter.kt` (owns `RECGOV_CART_URL`, `recgov_*` codes, `canFulfil` via `RecGovCredentialsConfigured`), `BookingActionService.kt` (no cart constant, no credential port, no vendor codes: registry → adapter → `canFulfil` → outcome), `model/api/BookingActionDto.kt` (delete `RECGOV_CART_URL`; the DTO's `cart_url` comes from the outcome), `model/booking/BookingActionCodes` (neutral codes only), `service/availability/WatchCapabilityService.kt` (`canFulfilAddToCart(requester, campsites)` through the registry's claiming adapter), `WatchTriggerCapabilityValidator.kt` (detail names the provider), `AtcTriggerActionHandler.kt` and `observability/*Metrics*.kt` (`roadtrip.booking.atc`, `roadtrip.booking.atc.duration` with a `provider` attribute; `atcFired(provider, outcome, …)`), `grafana/dashboards/recgov-atc.json` (new metric names, `provider="recgov"` filter), `route/api/BookingRoutes.kt` OpenAPI text.
- Test: `BookingActionServiceTest` (a fake adapter returning its own cart URL; `canFulfil` false → the no-credentials outcome; no rec.gov literal in the service test), `RecGovBookingAdapterTest` (cart URL and codes), `BookingAdapterRegistryTest`, `WatchCapabilityServiceTest` (a second fake adapter for another provider proves the gate is per adapter), `TriggerActionHandlerTest` (metric attribute `provider`), `OtelRoadtripMetricsTest`, `scripts/test_grafana_*` if the dashboard is checked.

- [ ] Steps: failing service tests → port → adapter → service → capability gates → metrics → dashboard → gate → commit `refactor(booking): the adapter owns its cart, credentials, codes, and metrics`.

---

### Task 4: Provider-keyed credentials (#735)

**Files:**
- Create: `V60__user_booking_credentials.sql` (table per the spec; copy `user_settings.recgov_username`/`recgov_password_cipher` into provider `recgov` rows where the username is not null, `ON CONFLICT DO NOTHING`; old columns stay), `repo/UserBookingCredentialsRepo.kt` (`find(user, provider)`, `save(user, provider, username, cipher)`, `clear(user, provider)`, `userIdsWithCredentials(provider)`), `service/settings/BookingCredentialPorts.kt` (`fun interface BookingCredentialsConfigured { fun isConfigured(provider: BookingProvider, user: UserId): Boolean }`).
- Modify: `repo/UserSettingsRepo.kt` (delete the `recgov_*` members), `service/settings/RecGovCredentialService.kt` (reads/writes provider `recgov` through the new repo; implements the provider-keyed port for `recgov`), `RecGovAccountPorts.kt` (`RecGovCredentialsConfigured` becomes a thin adapter over the provider-keyed port or is deleted), `di/ServiceModule.kt`, `service/scheduler/RecGovKeepaliveJob.kt` (`userIdsWithCredentials(RECGOV)`), settings service/routes as the compiler requires (DTOs and URLs unchanged).
- Test: `UserBookingCredentialsRepoTest` (round-trip, per-provider isolation, V60 replay copies a seeded V53 row and is idempotent), `RecGovCredentialServiceTest` (unchanged contract on the new repo), `RecgovSettingsRoutesTest`, `RecGovKeepaliveJobTest`, `UserSettingsRepoTest` (no `recgov_*` members).

- [ ] Steps: failing repo test → V60 → repo → ports → services → gate → commit `refactor(settings): booking credentials keyed by provider`.

---

### Task 5: Copy follows the provider; frontend cart copy (#735)

**Files:**
- Modify: `service/notification/email/EmailContentAtcResultRenderer.kt` and `slack/SlackContentAvailabilityRenderer.kt` (the provider's display name and cart URL from the outcome/target; no `recreation.gov` literal on a neutral path; rec.gov's display name lives with the rec.gov adapter/provider display object), `service/notification/common/WatchOpening`/ATC result models as needed to carry `provider` and `cartUrl`, `frontend/src/lib/strings.ts` `bookingCopy.heldInCart`/`openCart` (render the served `booking_system`; `VENDOR` stays for the rec.gov settings panel), `frontend/src/features/availability/AvailabilityWeek.tsx` ~206-214 (cart row copy from `booking_system`), tests on both sides.

- [ ] Steps: failing renderer tests → renderers → frontend copy → both gates → commit `refactor(notifications): booking copy names the provider that held the site`.

---

### Task 6: Docs (#735, #736)

**Files:** `docs/reservation-providers.md` (aliases: primary + aliases, one claim rule, preference order, target resolution; the booking port: adapter-owned cart/credentials/codes/metrics; credentials table), `docs/reservation-providers/recgov.md`, `docs/backend-architecture.md` (the port), `DATA_SOURCES.md` (Campflare writes Campflare identity plus rec.gov aliases).

- [ ] Steps: edit → commit `docs: booking aliases and the vendor-owned booking port`.
