# Region boundaries

A region — a state, a province, a national park — is an **area**. The map has always
treated one as a pin: search resolved it to a centroid, the camera flew to a zoom picked
off a fixed ladder, and "near here" was a radius guess. This doc is what exists now, and
what is still missing.

## What ships today

**Search resolves a region as a region.** `/api/geocode` carries the upstream's `bbox`
through to the client (`MapboxGeocoder` → `GeocodeResult.bbox` → `GeocodeResultDto.bbox`,
`[west, south, east, north]`, absent when the upstream reports no extent). A geocoded
feature whose `place_type` is `country`, `region` or `district` becomes a `REGION` search
result carrying that extent, and picking it calls `fitBounds` on the region rather than
`flyTo` on a point inside it. A town keeps the fixed-zoom `flyTo` it always had — see the
comment in `frontend/src/features/trip/search-results.ts` for why.

**The map can draw a region's boundary.** `frontend/src/map/region-boundary.ts` installs a
fill + outline pair from any `Polygon`/`MultiPolygon`, ordered like the route corridor is:
fill under the basemap's first symbol layer so labels stay readable, outline under the pins
so a late-arriving boundary does not draw over every dot. It is generic over the geometry —
nothing in it changes when park polygons arrive.

**Where the geometry comes from.** `frontend/src/map/regions.ts`, and it is honest about
having very little: the only region polygons in this repo are the US state boundaries in
`data/us-states.geojson`, already fetched for the static state-lines overlay and reused
from the same query cache. A region with no geometry still resolves and still frames — it
just does not draw an outline.

## What is missing, and what it would take

### 1. Park boundary geometry (ETL)

There is **no park boundary data in this repo and no ETL that fetches any**. The `pois`
table's `geom` column is `geometry(Geometry, 4326)` with a GIST index, so it has always
been able to hold a `Polygon`/`MultiPolygon` — the V1 migration comment says so in as many
words — but the current canonical catalog (`V38__canonical_catalog.sql`) constrains
`poi_type` to `campground`, `tesla_supercharger`, `planet_fitness_location`. There are no
park rows to attach a footprint to.

The nearest thing to a park-geometry fetcher is `scripts/fetch_arcgis_layer.py`, and note
what its `--return_centroid` flag does: for big polygon layers it asks the upstream for
centroids and **discards the geometry**, because full polygons overflow the 16MB response
cap. Boundary ingestion is precisely the case that flag exists to avoid.

Filling this in needs, in order:

1. **A source.** PAD-US (USGS Protected Areas Database) and the NPS boundary
   FeatureServer are the obvious candidates for the US; BC Parks and Parks Canada have
   their own ArcGIS layers. Register it in the POI registry like any other data source —
   `docs/adding-a-data-source.md` is the procedure.
2. **A paging fetcher that keeps geometry.** `fetch_arcgis_layer.py` already pages a
   FeatureServer; a boundary layer needs geometry retained and therefore a smaller page
   size than the current 2000, or per-feature fetches. Simplification (ST_SimplifyPreserveTopology
   at a serving tolerance) belongs in the transform, not the fetch — keep the raw capture raw.
3. **A region entity.** Either a `poi_type` the `pois` check constraint admits, or a
   separate `regions` table. A separate table is the better shape: a region is not a
   destination pin, it has no booking provider and no availability, and adding it to `pois`
   means every serving query grows a "and not the polygons" clause.
4. **Serving.** A boundary is far too big for the `/api/pois` bbox payload. It wants either
   its own endpoint returning one region's simplified geometry by id, or vector tiles.
   Whichever it is, it is a `repo` → `service` → `route` path like every other.

### 2. "Inside this region" as a query

This is the part that **cannot be faked and is not built**. Today the only spatial
containment the app expresses is the route corridor: `PoiServingRepo` builds a buffered
corridor polygon and filters with `ST_Within(ST_Centroid(geom), corridor.poly)`.
"Campgrounds in Yosemite" is the same shape against a region's geometry instead of a
corridor — the PostGIS side is a few lines and the GIST index is already there. What
is missing is only the left-hand side: a region row with a real footprint to intersect
against.

Until that exists, do not approximate it with a radius around a region centroid. A circle
around the middle of a long park is wrong in both directions at once, and the whole point
of this work is that a region is not a point.

### 3. Region search server-side

`/api/pois/search` searches the POI catalog only, and `useSearchResults` asks it for
`campground` alone — so every region result today comes from the geocoder. Once region rows
exist they should join that index, which makes a park hit outrank the geocoder's fuzzy
match on the same name and gives the boundary an id to fetch geometry by instead of a name
to match on.
