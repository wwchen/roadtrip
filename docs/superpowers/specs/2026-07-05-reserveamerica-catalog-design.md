# ReserveAmerica catalog — design

**Date:** 2026-07-05
**Status:** approved (brainstorming), pending implementation plan
**Vendors:** ReserveAmerica / Active Network — `alberta-provincial` (ABPP) and
`new-york-state-parks` (NY)

## Problem

ReserveAmerica POIs are **catalogless**: they carry a `provider_ref`
(`{contract_code, park_id}`) but have zero linked `reservables`, so
`AvailabilityServiceImpl.poiReservablesAvailability` falls to
`cataloglessProviderAvailability` — a live, render-only path that returns
synthetic sites and **cannot apply `site_type` filters**. As of 2026-07-05
this is 266 of 320 catalogless campground POIs (108 ABPP + 158 NY).

This was previously believed to be *structural* ("ReserveAmerica has no
catalog to import"). That is false. The site roster is available and the
catalog is importable; RA has simply never had a sites ETL. Cataloging it
moves all 266 POIs onto the normal cataloged availability path.

## Scope

**In:** the ReserveAmerica catalog path end-to-end (fetch → parse → ETL →
joiner → registry wiring), the two vendor/architecture docs, and a clear
**sunset demarcation** of the surviving catalogless code.

**Out:** the 55 non-RA catalogless strays. These are accepted as legitimate
**upstream bad data**, not ingestion bugs:

- **Aspira (25):** every one has `resourceLocationId: null` (23 Parks
  Canada + Wells Gray BC + Camano Island WA). The catalog exists (aspira_pc
  11,860 / bc 10,650 / wa 7,212 reservables); the POIs lack the join key.
- **RecGov (29):** overwhelmingly non-campsite facility types — day-use
  areas, cabins, lookouts, group sites, boat ramps, visitor centers. A
  data-model question, not a missing catalog.
- **ReserveCalifornia (1):** Oceano Dunes SVRA — a non-`isStandardBookable`
  open-camping/OHV grid the RC sites ETL intentionally skips.

The catalogless branch therefore **stays** as the legitimate handler for this
residue. We are shrinking its population, not deleting it. It gets an
explicit sunset demarcation (below); if that demarcation proves muddy in
implementation, we escalate to instrumenting it (counter/log per catalogless
hit, tagged by source).

## Chosen approach: scrape-derived catalog

The `campsiteCalendar.do` HTML we already scrape for availability lists every
site as a `siteListLabel` row carrying `siteId`, a site label, and
`contractCode`/`parkId` in the `campsiteDetails.do` href. We derive the
catalog from that same source.

**Decisive property — identity by construction.** The availability adapter
keys observations as `rid() = "site:reserveamerica_${contractCode.lowercase()}:$siteId"`
and `catalogAvailability` binds catalog rows via `statuses[reservable.vendorId]`.
Because the catalog `siteId` and the availability `siteId` come from the *same*
`campsiteCalendar.do` document, `vendor_id == siteId` is true **by
construction** — there is no cross-system id-reconciliation risk.

### Dead alternative: Active developer API (`api.amp.active.com`)

The Campground/Campsite Search API (`camping/campgrounds`, `camping/campsites`)
documents exactly the catalog we want — `SiteType` ("<= 40 FT. ELECTRIC -
PREMIUM"), `Loop` ("CONIFER RIDGE"), `Maxpeople`, `Maxeqplen`, hookups — keyed
by the same `(contractCode, parkId)` we store, with a numeric `SiteId` that
almost certainly matches the scrape's `siteId`.

**It is decommissioned.** Verified 2026-07-05: the endpoint returns
`HTTP 403 Forbidden` from `server: awselb/2.0` for every caller —
- no key and dummy key → app-level "Developer Inactive";
- a *valid* provisioned key (`roadtrip: ztkdunkx43ja5k3dx9dnwx96`) → ELB 403;
- from our local egress IP, from Anthropic's WebFetch egress (a different IP),
  **and from Active's own I/O Docs interactive console** signed in as an
  affiliate.

The load balancer rejects requests before the app; Active's own tooling can't
reach it; the docs footer reads © 2017. This is not an IP-allowlist or key
problem we can resolve — the API is gone. It is **not** a future enrichment
option. Recorded in `reserveamerica.md` so no one re-investigates it.

**Accepted tradeoff — thin `site_type`.** The calendar roster reliably yields
`siteId` + site number; real `loop` is absent (the calendar's `loopName` is a
pagination bucket like "Sites 036-049") and `site_type` is only derivable from
brittle per-site attribute markup. So RA ships with site-number `name`, correct
availability binding, and `loop`/`site_type` left null rather than
half-parsed. The **only** remaining route to rich `loop`/`site_type` is a
`campsiteDetails.do` per-site scrape (option C) — documented as future work,
explicitly NOT the dead developer API.

## Components

Each mirrors an existing RecGov/Aspira/ReserveCalifornia analog.

1. **Data source** — `scripts/fetch_reserveamerica_campsites.py`, two registry
   rows (`reserveamerica-campsites-abpp`, `-ny`). Reuses
   `fetch_reserveamerica.py`'s WAF session (JSESSIONID + browser UA) and
   directory walk for the parkId list, then captures `campsiteCalendar.do`
   per park (one near-term date window, paginating `startIdx` by 25 until
   `resulttotal`) → `data/raw/reserveamerica-campsites-<contract>/<ts>/campsite-<parkId>.json`
   (envelope-wrapped HTML). The roster, not availability, is the goal, so one
   window suffices.

2. **Parser** — extract a shared `siteRows(html)` helper from
   `ReserveAmericaAvailabilityParser` (the row-finding regex is common to both
   readers), then add a `ReserveAmericaCatalogParser` returning
   `(siteId, name=label, loop?, siteType?)` per row. No duplication.

3. **`ReserveAmericaSitesEtl : SourceEtl<_, ReservableEtlOutput>`** — under
   `service/etl/vendors/reserveamerica/`. Per site row emits:
   ```
   ReservableRepo.Input(
     rid     = ReservableId(SITE, "reserveamerica_${contract.lowercase()}", siteId),
     name    = label,
     loop    = loop,      // if present
     siteType = siteType, // if present
     raw     = withSynthetic(rawSite, {_parent_contract_code, _parent_park_id}),
   )
   ```
   The `reserveamerica_${contract}` vendor string is **mandatory**, not a
   choice: it must equal the adapter's `rid()` vendor segment or availability
   will not bind.

4. **`ReserveAmericaPoiReservableJoiner : PoiReservableJoiner`** — links on
   `reservables.raw->>'_parent_contract_code' = pois.provider_ref->>'contract_code'`
   AND `..._park_id = ...->>'park_id'`, scoped to
   `pois.source IN ('alberta-provincial','new-york-state-parks')` and
   `reservables.vendor LIKE 'reserveamerica_%'`. Mirrors the RC joiner incl.
   `sweepStaleLinks`.

5. **Registry wiring** — 3 edits, no jOOQ codegen (tables exist):
   - `config/poi-registry.yaml`: `data_sources` rows, a `reservable_data`
     group with the two SitesEtl rows, and a `poi_reservable_joiner` row.
   - `EtlOrchestrator.etlRegistry`: two SitesEtl slug→instance entries.
   - `EtlOrchestrator.joinerRegistry`: the joiner.

## Data flow

```
fetch_reserveamerica_campsites.py
  → data/raw/reserveamerica-campsites-<contract>/…
  → ReserveAmericaSitesEtl
  → reservables (vendor=reserveamerica_<contract>, vendor_id=siteId)
  → ReserveAmericaPoiReservableJoiner
  → reservable_pois links
  → (request time) findByPoi returns rows → cataloged path
  → catalogAvailability binds statuses[vendorId] (vendorId == siteId, by construction)
  → real availability; site_type filter now applies
```

RA (266 POIs) drops out of the catalogless population.

## Sunset demarcation (catalogless code)

`cataloglessProviderAvailability` in `AvailabilityServiceImpl` gets an explicit
contract doc-comment: it serves **only** POIs with no obtainable catalog due to
**upstream** data gaps, with the known residue enumerated inline (Aspira null
`resourceLocationId`; RecGov non-campsite facility types; RC open/SVRA grids).
It notes that any vendor with an obtainable catalog (RA, now) must not depend on
it, and that it is earmarked for removal when the residue clears. Assert the
scope cheaply in code where possible.

**Fallback:** if the demarcation cannot be made clean, instead instrument the
path — a counter/log per catalogless hit tagged by `source` — so the residue
stays observable.

## Error handling

- WAF 403 → reuse session prime (JSESSIONID + browser UA) with retry.
- Park calendar with zero sites → skip; POI stays catalogless (legit).
- Label row without `siteId` → skip.
- Empty per-tenant capture → ETL `validate` returns `Bad`.
- Removed sites → joiner `sweepStaleLinks`.

## Testing

- **Identity invariant (most important):** assert the `vendor_id` the SitesEtl
  emits for a row equals the `siteId` the availability parser emits for that
  same HTML — locks the by-construction binding against future parser drift.
- Catalog-parser roster extraction from a real captured fixture.
- Regression: the shared `siteRows` refactor keeps availability parsing green.
- SitesEtl transform: fixture → reservables, asserting `vendor == "reserveamerica_ny"`.
- Joiner: link on `(contract, park)` + stale sweep.

## Docs

- **New `docs/reservation-providers/reserveamerica.md`** (owns wire details):
  two-Active-systems summary; tenants (`shop.albertaparks.ca` /
  `newyorkstateparks.reserveamerica.com`, contract codes); ID model
  (`provider_ref = {contract_code, park_id}`; reservable identity
  `site:reserveamerica_{contract}:{siteId}`); endpoint catalog — the
  `campsiteCalendar.do` site-roster shape (chosen) **and the developer API
  finding recorded as DEAD (Campground/Campsite Search — documents rich
  `SiteType`/`Loop` but the endpoint is decommissioned: `awselb/2.0` 403 to
  all callers incl. Active's own I/O Docs, © 2017) so it is not
  re-investigated**; catalog status; the `campsiteDetails.do` scrape as the
  only future `loop`/`site_type` path; adapter notes.
- **`docs/reservation-providers.md`** (architecture contract, no wire
  details): matrix note → "sites cataloged from the campsite calendar; see
  `reserveamerica.md`"; closing pointer → real `reserveamerica.md` link.

## Pre-implementation check (replaces the old dev-API probe)

Capture one Alberta park's `campsiteCalendar.do` and confirm: (a) it lists
*all* sites incl. seasonally-closed ones, and (b) how rich the label block is
(does it carry loop / site type?). A look, not a blocker — it calibrates the
catalog parser and the `site_type` expectation.
