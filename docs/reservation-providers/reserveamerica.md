# ReserveAmerica / Active Network

Wire details for the ReserveAmerica (Active Network) tenants: `alberta-provincial`
(Alberta provincial parks) and `new-york-state-parks` (New York state parks).
This doc owns the vendor wire shapes; `../reservation-providers.md` owns the
architecture contract.

## Summary

ReserveAmerica exposes **two** Active Network systems, and neither is complete
alone:

- **Consumer reservation site** (`shop.albertaparks.ca`,
  `newyorkstateparks.reserveamerica.com`) — scraped over HTTP, no key. Serves
  both live availability (`campsiteCalendar.do`) **and** the site roster (the
  same page). This is what we use, for both availability and the catalog.
- **Developer API** (`api.amp.active.com`) — documents a richer catalog but is
  **decommissioned** (see [Dead developer API](#dead-developer-api)).

We derive the site catalog from the consumer site's `campsiteCalendar.do`
roster, so the catalog `siteId` is byte-for-byte the id the availability adapter
keys on — catalog rows bind to availability by construction.

## Tenants

| `source` | contract | host |
|---|---|---|
| `alberta-provincial` | `ABPP` | `shop.albertaparks.ca` |
| `new-york-state-parks` | `NY` | `newyorkstateparks.reserveamerica.com` |

Add a tenant by appending to the `TENANTS` table in
`scripts/fetch_reserveamerica.py` and registering its data sources + a SitesEtl
instance (see `config/poi-registry.yaml`).

## ID model

- **POI** `provider_ref` = `{contract_code, park_id}` (both strings), e.g.
  `{"contract_code": "ABPP", "park_id": "330101"}`. `park_id`/`facilityID` is
  unique only *within* a `contractCode`.
- **Campsite** identity is the canonical catalog row id. `vendor_id` is the
  numeric `siteId` in the calendar's `campsiteDetails.do` href.
  `vendor` is **per-tenant** (`reserveamerica_abpp`, `reserveamerica_ny`), not a
  flat `reserveamerica`, so `vendor_id == siteId` and catalog rows bind to
  availability observations directly.
- The joiner (`ReserveAmericaPoiReservableJoiner`) links reservable → POI on the
  `(contract_code, park_id)` pair carried in `reservables.raw`
  (`_parent_contract_code`, `_parent_park_id`). Both keys must match, so a
  `park_id` that collides across contracts never cross-links.

## Endpoint catalog

### `GET campsiteCalendar.do` — availability + roster (the catalog source)

```
https://{host}/campsiteCalendar.do?page=calendar&contractCode={c}&parkId={p}
    &calarvdate={MM/DD/YYYY}&sitepage=true&startIdx={n}
```

HTML. Each site is a `siteListLabel` row:

```html
<div class='siteListLabel'>
  <a href='/camping/.../campsiteDetails.do?contractCode=NY&siteId=253478&parkId=489'
     aria-label='Site: 039 (253478)'>039</a>
</div>
```

- Roster fields extracted: `siteId` (253478), `parkId` (489), and the label
  text ("039") → `reservables.name`.
- Paginated: `startIdx` steps by 25; `<span id='resulttotal_dr_top'>` gives the
  count. `ReserveAmericaAvailabilityParser.siteRows` splits rows; the
  availability parser reads status cells, `ReserveAmericaCatalogParser` reads
  the roster.
- `loop` and `site_type` are **not** captured: the calendar's `loopName` cell is
  a pagination bucket ("Sites 036-049"), not a real loop, and site type is only
  present as brittle per-cell attribute markup. Both ship null.

### Dead developer API

`api.amp.active.com/camping/{campgrounds,campgrounds/details,campsites}`
(Campground Search / Details / Campsite Search) documents exactly the richer
catalog we'd want — `SiteType` ("<= 40 FT. ELECTRIC - PREMIUM"), real `Loop`
("CONIFER RIDGE"), `Maxpeople`, `Maxeqplen`, hookup flags — keyed by the same
`(contractCode, parkId)` we store, with a numeric `SiteId`.

**It is decommissioned. Do not re-investigate.** Verified 2026-07-05: the
endpoint returns `HTTP 403` from `server: awselb/2.0` for **every** caller —
no key, a dummy key, a valid provisioned key (`ztkdunkx43ja5k3dx9dnwx96`), our
egress IP, Anthropic's WebFetch egress (a different IP), **and Active's own
I/O Docs interactive console** signed in as an affiliate. The load balancer
rejects requests before the app; the docs footer reads © 2017. The API is
gone; rich `site_type`/`loop` is not obtainable from it by anyone.

The **only** future path to rich `loop`/`site_type` is a `campsiteDetails.do`
per-site scrape (linked from each calendar row) — not the dead API.

## Catalog status

Cataloged from the `campsiteCalendar.do` roster via `ReserveAmericaSitesEtl`
(per tenant) + `ReserveAmericaPoiReservableJoiner`. `name` = site number;
`loop`/`site_type` = null. A POI only shows availability once its catalog rows
are linked — POIs without linked `reservables` report an empty window (there is
no live render-only fallback).

## Adapter design notes

Availability reads the live matrix (`ReserveAmericaAvailabilityParser`); the
catalog parser (`ReserveAmericaCatalogParser`) shares row-splitting via
`siteRows`, so catalog `vendor_id` equals the availability `siteId` by
construction. Watches stay off (`supportsAlerts = false`) pending cadence/load
validation for Active Network.
