# Tenant registry and vendor display copy (audit phase 4b)

Design for phase 4b of the 2026-09-09 architecture audit (finding 9, issue #738).
Builds on phase 4a (`2026-09-10-booking-port-design.md`): booking aliases, the
booking port, and the drawer's booking identity.

## Problem

The same vendor facts live in Kotlin literals, YAML args, a migration, and a
frontend table, and none of them agree:

- Aspira's three tenants are rows in `AspiraTenants.kt` with UI copy
  (`bookingSystemLabel`, `ctaLabel`) in the provider layer; ReserveAmerica's two
  are a companion-object map; `PoiRegistry.reserveAmericaSources()` parses the
  same rows out of `poi-registry.yaml` and nothing in production reads it.
- The Aspira tenant code is spelled four ways: `aspira_pc` in `AspiraTenants`,
  `tenant: pc` in YAML, `removePrefix("aspira_")` in DI, and `bc` as a constant
  in `BcParksCampgroundsEtl` plus a default on `DataProviderRef.BcParksCampsite`.
- Every Aspira reserve deeplink is dated in `America/New_York`; two of three
  tenants are Pacific.
- `bookingHorizonDays` is declared per tenant, per YAML row, and per adapter;
  only the adapter constant is honoured.
- Backend copy lives in four `*BookingDisplay` objects plus `AspiraBookingDisplay`
  and `ExternalInfoLinkLabels` ("View on recreation.gov" beside "Recreation.gov").
- The frontend keeps `AGENCY_BY_HOST`, `AGENCY_BY_VENDOR`, `labelFromHost`,
  `humanizeAgency`, `reserveLabel`, and `sameVendor` to reconcile its own guesses
  with the `booking_system` the backend already serves. `aspira → "Aspira"` there
  disagrees with `"Aspira NextGen"` here.
- Phase 4a left the drawer's booking identity gated on `BookingAdapterRegistry`,
  which holds the rec.gov adapter only when the ATC companion is wired, so a pin
  renders differently per environment.

## Design

### One registry section

`poi-registry.yaml` gains a top-level `booking_providers` section, one row per
`BookingProvider` member:

```yaml
booking_providers:
  - id: recgov
    display_name: Recreation.gov
    sells: true
    tenants:
      - host: www.recreation.gov
  - id: campflare
    display_name: Campflare
    sells: false
    tenants:
      - host: campflare.com
  - id: aspira
    display_name: Aspira NextGen
    sells: true
    tenants:
      - code: pc
        host: reservation.pc.gc.ca
        display_name: Parks Canada
      - code: bc
        host: camping.bcparks.ca
        display_name: BC Parks
      - code: wa
        host: washington.goingtocamp.com
        display_name: Washington State Parks
  - id: reserveamerica
    display_name: ReserveAmerica
    sells: true
    tenants:
      - code: ABPP
        host: shop.albertaparks.ca
        display_name: Alberta Parks
      - code: NY
        host: newyorkstateparks.reserveamerica.com
        display_name: New York State Parks
  - id: reservecalifornia
    display_name: ReserveCalifornia
    sells: true
    tenants:
      - host: www.reservecalifornia.com
```

- `id` is the `BookingProvider` id. `display_name` is what a person calls the
  vendor. `sells` says whether a person books on this vendor's site (Campflare
  is an aggregator: its link is "View on", never "Reserve on").
- A tenant is one host a vendor runs. `code` is the tenant key as it is stored
  in `booking_provider_ref` (`pc:` for Aspira, `ABPP:` for ReserveAmerica) and
  as ETL rows name it in `args.tenant` / `args.contract`. Single-tenant vendors
  have one row with no code. `display_name` on a tenant is the site a person
  books on; absent, the vendor's name is used.
- No `time_zone` and no `booking_horizon_days` on a row (see "Decisions").

Kotlin: `model/metadata/registry/BookingProviderEntry` and `TenantEntry` are the
serializable rows; `model/domain/provider/BookingTenant(provider, code, host,
displayName)` and `BookingVendorProfile(provider, displayName, sells, tenants)`
are the domain types; `model/metadata/registry/TenantRegistry` is built from the
section and answers:

- `profile(provider)`, `tenantsOf(provider): List<BookingTenant>`
- `tenant(provider, code)`, `tenantByHost(host)`
- `displayName(provider)`; `displayName(ref: BookingProviderRef)` = the ref's
  tenant name when the ref names a known tenant, else the provider name
- `sells(provider)`
- `ctaLabel(ref)` = `"Reserve on <name>"` when the provider sells, else
  `"View on <name>"`; `linkLabel(host)` labels an info link, so it is always
  `"View on <name>"`, null for unknown hosts.

Validation at boot (`PoiRegistry.validate`): every `BookingProvider` member has
exactly one row; tenant codes are unique within a vendor; hosts are unique across
the section; every `poi_data`/`campsite_data` ETL row whose `args` carry
`tenant` (Aspira adapters) or `contract` (ReserveAmerica adapters) names a tenant
of the matching vendor, and its `args.host`, when present, equals that tenant's
host. `PoiRegistry.reserveAmericaSources()`, `ReserveAmericaSourceConfig`, and
the `booking_horizon_days` args are deleted.

### Adapters read the registry

- `AspiraAvailabilityProvider` and `ReserveAmericaAvailabilityProvider` take
  `List<BookingTenant>` (their vendor's rows, from `TenantRegistry.tenantsOf`)
  instead of the hand-built maps; `AspiraTenant`, `AspiraTenants`,
  `ReserveAmericaTenant`, and the companion map are deleted. The DI
  `removePrefix("aspira_")` derivation goes with them: the registry code is the
  stored prefix.
- `AspiraTenant.vendorCode` (`aspira_pc`) is only used as a non-null flag inside
  the Aspira provider today; the flag becomes a boolean, and no `aspira_*`
  string survives outside the applied migrations V12 and V45.
- `BcParksCampgroundsEtl` takes its tenant from `args.tenant: bc` like the other
  Aspira ETLs; `DataProviderRef.BcParksCampsite.tenant` loses its `"bc"` default.

### Display copy comes from the registry

- `RecGovBookingDisplay`, `CampflareBookingDisplay`, `AspiraBookingDisplay`,
  `ReserveAmericaBookingDisplay`, `ReserveCaliforniaBookingDisplay` are deleted.
  `CampgroundCta` takes the `TenantRegistry`; each `CampgroundCtaProvider`
  answers `bookingSystem` with `registry.displayName(ref)` and labels its reserve
  CTA with `registry.ctaLabel(ref)`. Campflare's info link is
  `registry.linkLabel("campflare.com")`.
- `booking_system` therefore reads `Parks Canada`, `BC Parks`, `Washington State
  Parks`, `Alberta Parks`, `New York State Parks`, `Recreation.gov`, `Campflare`,
  `ReserveCalifornia`; a ref whose tenant the registry does not know reads the
  vendor name (`Aspira NextGen`, `ReserveAmerica`).
- `ExternalInfoLinkLabels` asks `registry.linkLabel(host)` first, so a stored
  recreation.gov or campflare.com URL reads the vendor's registered name; the
  agency hosts (fs.usda.gov, nps.gov, …) stay as they are, they are agencies,
  not vendors.
- `BookingAdapter.displayName` is removed from the port; `AtcTriggerActionHandler`
  and `WatchCapabilityService` ask `registry.displayName(target.parentRef)`.
- The rec.gov reserve CTA keeps the stored reservation URL when its host is a
  registered rec.gov tenant host and otherwise builds
  `RecGovBookingUrl.campground(facilityId)`, so an alias never labels a foreign
  URL "Reserve on Recreation.gov" (the 4a leftover).

### The booking identity follows the registry

`CampgroundService.bookingRef` becomes: the first of (primary, aliases in
order) whose vendor `sells`, else the serving availability provider's claim,
else the declared primary. No dependency on `BookingAdapterRegistry` or the
companion channel, and the rule moves into one `BookingIdentityResolver` in
`service/availability` that the campground drawer and the campsite rows share.
`ShippingProfileCompanionConfigTest` keeps its assertion but is re-aimed: the
CTA no longer needs `companion-base-url`, while the `atc` action still does —
without it `RecGovBookingAdapter` is never registered and every hold refuses.

### Dates come from the POI

`CampgroundCta.computeCtas` takes the POI's `PoiDateContext`; the Aspira
deeplink is dated from `context.earliestDate` (the campground's own zone, which
`AvailabilityDateResolver` already computes for `earliest_date`). The
`America/New_York` anchor, the `Clock` on `CampgroundCta`, and the TODO are
gone. `WatchAlertDispatcher`, which only needs `bookingSystem`, is unchanged in
shape.

`earliestDate` is the earliest *bookable* arrival, not today: it rolls to
tomorrow after the 18:00 local cutoff, exactly as the availability picker's own
window does. So after that hour the deeplink proposes tomorrow's arrival, which
is the first night the picker beside it will offer.

### The wire carries names beside every slug

- `CampsiteDto.booking_system: String?` on `/api/pois/{id}/campsites`: the name
  of the site this row's booking identity opens, by the same resolver as the
  drawer.
- `AddToCartResponseDto.provider_display` and `ApiErrorSchema.provider_display`
  beside the existing `provider` slug.

### The frontend renders what it is served

- `booking-links.ts` loses `AGENCY_BY_HOST`, `AGENCY_BY_VENDOR`, `agencyLabel`,
  `providerLabel`, `knownProviderLabel`, `labelFromHost`, `humanizeAgency`.
  `bookingLabel(row)` is `Book on <row.booking_system>` or the neutral "Book".
  `SiteMatrix` passes `row.booking_system` as the popover's agency.
- `AvailabilityWeek`: the hold toast names `answer.provider_display`, the
  failure toast names the envelope's `provider_display`; `holdProviderName`,
  `refusingProviderName`, `sameVendor` are deleted; neutral copy when absent.
- `site-detail-facts.ts`: the "Provider" fact becomes "Booking site" from
  `booking_system`; "Provider ID" stays.
- `campground-detail.ts` loses `reserveLabel`: the backend always labels a
  reservation URL (registry name when the host is a tenant, else
  `ExternalInfoLinkLabels`), so the frontend fallback for a bare `reserve_url`
  is `bookingCopy.reserve`.
- `VENDOR` in `lib/strings.ts` stays for the rec.gov account panel and its
  errors, which are rec.gov's own surface; no other module imports it.

## Decisions

- **No `time_zone` per tenant.** The audit listed it; a tenant's zone is the
  wrong fact (Parks Canada spans six zones) and the POI's geographic zone is
  already computed for `earliest_date`. Dating the deeplink from the POI's date
  context fixes the Pacific tenants and needs no new data.
- **No `booking_horizon_days` per tenant.** Every tenant of a vendor shares its
  horizon today and only the adapter constant is honoured; the registry does
  not restate what the adapter declares. If a tenant ever differs, the row gains
  the field and `BookingHorizonResolver` reads it through the provider.
- **Tenant display names are the booking sites.** `booking_system` for an
  Aspira BC row changes from "Aspira NextGen (BC Parks)" to "BC Parks", which is
  what the frontend's table already showed on the same screen.
- **CTA labels are templates, not rows.** "Reserve on <name>" / "View on <name>"
  replace six hand-written labels ("Book WA State Park", "Reserve on
  parks.canada.ca", …). The name is data; the verb is not.
- **`sells` is a registry fact**, not a runtime capability: whether a person can
  book on a vendor's site does not depend on whether this process holds an ATC
  adapter for it.
- V12 and V45 are applied migrations and keep their `aspira_*` strings.

## Out of scope

- Agency info-link copy (`ExternalInfoLinkLabels` agency hosts) and the
  `regionalParkSearch` table in the frontend.
- Horizons from the vendor's own date schedule (the `AspiraTenant` KDoc idea).
- Unifying `poi_data.agency` with tenant display names: agency is who manages
  the park, the tenant is where you book.

## Testing

- `PoiRegistryValidatorTest`: a missing vendor row, a duplicate host, an ETL
  `args.tenant` naming no tenant, and an `args.host` that disagrees each fail
  with a named error; the shipped YAML validates.
- `TenantRegistryTest`: `displayName(ref)` per variant (known tenant, unknown
  tenant, single-tenant vendor), `ctaLabel`, `linkLabel`.
- `CampgroundCtaTest`: every label pinned to the registry values; the Aspira
  deeplink dated from a Pacific date context on a day where Eastern differs.
- `PoiServiceTest`: aliased Campflare pin books through rec.gov with an empty
  `BookingAdapterRegistry`; Campflare-only pin stays Campflare; `booking_system`
  per tenant.
- `CampsiteRoutesTest`/campsites response: `booking_system` per row, aliased
  and plain.
- `BookingRoutesTest`: `provider_display` on the hold response and on a refusal.
- Frontend: `booking-links.test.ts` shrinks to templates and `bookingLabel`;
  `AvailabilityWeek.test.tsx` toasts read `provider_display`;
  `site-detail-facts.test.ts`; `campground-detail.test.ts` fallback label.
- Live on the local stack: an Aspira BC pin, a ReserveAmerica NY pin, an aliased
  Campflare pin, and a rec.gov pin each show the registry name in
  `booking_system`, the CTA, and the campsites' book buttons; `make qa`.

## Docs

`docs/reservation-providers.md` (adapter matrix, which wrongly says
"Pennsylvania"; booking seam; adding a provider is now a registry row plus an
adapter), `docs/reservation-providers/aspira.md` and `reserveamerica.md` (host
tables point at the registry), `docs/backend-architecture.md` (`TenantRegistry`
beside `BookingAdapterRegistry`; routes never read it), `DATA_SOURCES.md`,
`docs/adding-a-reservation-provider.md`, `docs/adding-a-data-source.md`,
`docs/glossary.md` ("tenant").
