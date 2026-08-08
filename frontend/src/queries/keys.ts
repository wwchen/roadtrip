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

  geocode: (q: string, autocomplete: boolean, limit: number, proximity?: string | null) =>
    ['geocode', q, autocomplete, limit, proximity ?? null] as const,

  route: (stops: unknown, radiusMiles: number) => ['route', stops, radiusMiles] as const,
} as const;
