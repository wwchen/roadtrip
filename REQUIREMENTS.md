# Roadtrip Map — Requirements

## Goal
A personal web-based map showing points of interest useful for roadtripping in a Tesla: charging, overnight stays, and workout/shower stops. The aim is a clean, filterable map — not a route planner replacement for the Tesla nav.

## User & context
- **User**: single user (owner), roadtripping in a Tesla across the continental US.
- **Usage**: primarily consulted on a phone browser during trips; also on laptop when planning.
- **Not in scope**: multi-user accounts, sharing, social features, booking/reservations.

## Platform
- Static web app. HTML + JS + a map library, deployable to GitHub Pages / Vercel / Netlify.
- No backend server required for the POC. If a data source requires a server-side proxy (CORS, API keys), introduce a minimal serverless function.
- Must work well on mobile Safari/Chrome.

## POI categories
Core (must-have for POC):
1. **Tesla Superchargers** — status/stall count if available.
2. **Planet Fitness** — locations (owner has a membership; useful for showers).
3. **Campgrounds** — general campgrounds, any type.

Extras (nice-to-have, add after core works):
4. **Free charging stations** (non-Tesla, free-to-use) — from PlugShare-equivalent data.
5. **State parks** — boundaries or markers; many allow camping.
6. **Hipcamp** — private land campsites.

Each category needs its own color/icon and its own show/hide toggle.

## Geographic scope
Continental US for the POC. Alaska/Hawaii/Canada/Mexico can come later.

## Data strategy (hybrid)
- **Static pre-fetched data** for categories that change slowly: Planet Fitness locations, state park boundaries, Hipcamp listings. Stored as GeoJSON in the repo, refreshed by a script on demand.
- **Live-ish data** for things that change often or benefit from freshness: Supercharger locations (new sites open regularly), free chargers (OCM). Can still be cached to a static file and refreshed daily via a GitHub Action, rather than hitting APIs at page load.
- Page load should not depend on third-party APIs being up. Data loads from the repo; refresh is an out-of-band job.

## Key features (POC)
1. **Map view** — pan/zoom, continental US, with markers clustered at low zoom levels to avoid a wall of pins.
2. **Filter toggles** — one toggle per POI category. State persists in URL or localStorage.
3. **Click marker → popup** — name, address, category-specific details (stall count, free/paid, amenities).
4. **Route planning (simple)** — input start + end, draw route, optionally filter POIs within a corridor (e.g. 10mi) of the route. Does NOT need to be a full Google Maps replacement — just a line and a buffer.
5. **Offline-capable** — service worker caches app shell, GeoJSON data files, and recently viewed map tiles so the map still works without signal.

## Non-goals (explicit)
- Real-time Supercharger availability (Tesla's own app does this).
- Reservations or bookings.
- User accounts / sync across devices.
- Turn-by-turn navigation.
- Anything outside North America.

## Success criteria for POC
- Open page on phone → map loads in <3s on LTE.
- Toggle categories on/off → markers update instantly.
- Enter a start/end → see a route line + POIs within a corridor around it.
- Lose signal mid-trip → map + POI data still usable for the area already loaded.

## Open questions
- **Data refresh cadence**: daily via GitHub Action? Weekly? Manual?
- **Tile provider**: OSM raster (free, limited), MapTiler/Mapbox (free tier, better looking, requires key), or Protomaps (self-host PMTiles, no key)?
- **Map library**: Leaflet (simplest, raster tiles) vs MapLibre GL (vector tiles, nicer UX, more setup).
- **PF and Hipcamp data**: neither has an open API — acceptable to scrape their public store locators for personal use? (Revisit when building the fetch script.)

## Next step
Research data sources for each POI category — coverage, license, access method, freshness. See `DATA_SOURCES.md`.
