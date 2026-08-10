// Query keys, in one place.
//
// Invalidation is how the migrated app replaces the `roadtrip:*` CustomEvent
// refetch bus, so a key typo is a silently-stale screen. Keeping every key here
// means an invalidation site and a fetch site cannot drift apart.
//
// Keys are hierarchical: invalidating `['watches']` invalidates every
// `['watches', …]` below it, which is what "a watch changed, refetch the list"
// wants.

export const queryKeys = {
  me: () => ['me'] as const,
  settings: () => ['settings'] as const,

  watches: {
    all: () => ['watches'] as const,
    list: (filters?: Readonly<Record<string, unknown>>) => ['watches', 'list', filters ?? {}] as const,
    detail: (id: string | number) => ['watches', 'detail', String(id)] as const,
  },

  pois: {
    all: () => ['pois'] as const,
    viewport: (bbox: readonly number[], zoom: number, categories: readonly string[]) =>
      ['pois', 'viewport', bbox, zoom, categories] as const,
    onRoute: (waypoints: unknown, radiusMiles: number, categories: readonly string[]) =>
      ['pois', 'on-route', waypoints, radiusMiles, categories] as const,
    detail: (id: string | number) => ['pois', 'detail', String(id)] as const,
    /**
     * A POI's display name only.
     *
     * Deliberately NOT `detail(id)`: that key holds the whole POI object, and a
     * query caching a bare string under it would hand the wrong type to whoever
     * reads it next. Same request, different cached value, so different key.
     */
    name: (id: string | number) => ['pois', 'name', String(id)] as const,
    search: (q: string, limit: number, categories?: readonly string[] | string) =>
      ['pois', 'search', q, limit, categories ?? null] as const,
  },

  campsites: {
    all: () => ['campsites'] as const,
    forPoi: (poiId: string | number) => ['campsites', String(poiId)] as const,
  },

  availability: {
    all: () => ['availability'] as const,
    forPoi: (poiId: string | number, startDate?: string, endDate?: string, siteType?: string) =>
      ['availability', String(poiId), startDate ?? null, endDate ?? null, siteType ?? null] as const,
  },

  dashboard: {
    all: () => ['dashboard'] as const,
    /**
     * Prefix over every poller query — each filtered list AND the summary, since
     * `pollersSummary` sits under this too.
     *
     * Needed because `pollers(filters)` is a LEAF key: `['dashboard','pollers',{}]`
     * does not prefix-match `['dashboard','pollers',{active:'true'}]`, so
     * invalidating `pollers()` would quietly refetch nothing after a "check now".
     * Reach for this when invalidating and for `pollers(filters)` when fetching.
     */
    pollersAll: () => ['dashboard', 'pollers'] as const,
    pollers: (filters?: Readonly<Record<string, unknown>>) =>
      ['dashboard', 'pollers', filters ?? {}] as const,
    pollersSummary: () => ['dashboard', 'pollers', 'summary'] as const,
    runs: (filters?: Readonly<Record<string, unknown>>) =>
      ['dashboard', 'runs', filters ?? {}] as const,
    runsForPoller: (pollerId: string | number, limit?: number) =>
      ['dashboard', 'runs', String(pollerId), limit ?? null] as const,
    changes: (filters?: Readonly<Record<string, unknown>>) =>
      ['dashboard', 'changes', filters ?? {}] as const,
    changesSummary: (poiId: string | number, dates?: readonly string[]) =>
      ['dashboard', 'changes', 'summary', String(poiId), dates ?? null] as const,
  },

  /**
   * A static GeoJSON asset served by the backend (state lines).
   *
   * Its own namespace rather than a `pois` entry: it is a file, not an API
   * resource, and it must not be swept up by a `['pois']` invalidation.
   */
  staticGeoJson: (url: string) => ['static-geojson', url] as const,

  geocode: (q: string, autocomplete: boolean, limit: number, proximity?: string | null) =>
    ['geocode', q, autocomplete, limit, proximity ?? null] as const,

  /**
   * A computed route, keyed on its stops alone.
   *
   * The corridor radius is deliberately NOT part of the key, even though
   * `requestRoute` sends it: the road geometry does not depend on it, only the
   * corridor polygon the response carries does — and the slider recomputes that
   * locally with turf. Keying on the radius would fire a routing request per
   * slider settle for a route that cannot have changed.
   */
  route: (stops: unknown) => ['route', stops] as const,
} as const;
