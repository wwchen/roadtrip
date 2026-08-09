// The viewport response cache.
//
// Port of the `viewportCache` ring inside web/app.js's bbox loader. It is its own
// module because it is the one part of the fetch loop with interesting rules and
// no dependencies at all.
//
// **Why this exists alongside TanStack Query.** A query key matches exactly, so
// panning by one pixel is a cache miss. This is a CONTAINMENT cache: a pan into a
// sub-bbox of a view already fetched is a hit, because the cached response covers
// more ground than the new viewport and MapLibre only paints the features that
// intersect the screen anyway. Query supplies the exact-match tier, the retry
// policy and the cancellation; this supplies the "I already have a superset"
// tier. Neither replaces the other.
//
// Three rules carried over, each load-bearing:
//   - Only NON-truncated responses may be stored. `truncated: true` means the
//     server dropped features past its per-category budget, so a contained
//     sub-view would render fewer pins than a real fetch would return. The caller
//     enforces this — see `useViewportPois`.
//   - Entries expire. POI rows and campground data change under us, and a
//     five-minute view of the world is the vanilla tolerance.
//   - Newest-first lookup over a ring of eight, so zooming back out to a cached
//     parent view still hits without the cache growing without bound.

/** `[west, south, east, north]` — the flat order `POST /api/pois` takes. */
export type CacheBbox = readonly [number, number, number, number];

export const VIEWPORT_CACHE_TTL_MS = 5 * 60 * 1000;
export const VIEWPORT_CACHE_MAX_ENTRIES = 8;

export interface ViewportCache<T> {
  /** The newest live entry whose bbox contains `bbox` under the same key, or null. */
  lookup(bbox: CacheBbox, key: string, now?: number): T | null;
  put(bbox: CacheBbox, key: string, value: T, now?: number): void;
  /** Live entry count, for tests and for reasoning about the ring. */
  readonly size: number;
}

export interface ViewportCacheOptions {
  ttlMs?: number;
  maxEntries?: number;
}

/** Whether `inner` sits entirely inside `outer`. */
export function bboxContains(outer: CacheBbox, inner: CacheBbox): boolean {
  return (
    outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3]
  );
}

interface CacheEntry<T> {
  bbox: CacheBbox;
  key: string;
  value: T;
  storedAt: number;
}

/**
 * A viewport cache instance.
 *
 * `now` is a parameter rather than a `Date.now()` call at the point of use so
 * expiry is testable without fake timers. It defaults to the real clock, so
 * callers never pass it.
 */
export function createViewportCache<T>({
  ttlMs = VIEWPORT_CACHE_TTL_MS,
  maxEntries = VIEWPORT_CACHE_MAX_ENTRIES,
}: ViewportCacheOptions = {}): ViewportCache<T> {
  const entries: CacheEntry<T>[] = [];

  const evictExpired = (now: number) => {
    for (let i = entries.length - 1; i >= 0; i--) {
      const entry = entries[i];
      if (entry && now - entry.storedAt > ttlMs) entries.splice(i, 1);
    }
  };

  return {
    lookup(bbox, key, now = Date.now()) {
      // Expire first, so the containment scan below cannot return a stale
      // superset that a later put would have replaced.
      evictExpired(now);
      for (let i = entries.length - 1; i >= 0; i--) {
        const entry = entries[i];
        if (entry && entry.key === key && bboxContains(entry.bbox, bbox)) return entry.value;
      }
      return null;
    },

    put(bbox, key, value, now = Date.now()) {
      entries.push({ bbox, key, value, storedAt: now });
      // Oldest out first: the newest entries are the ones a continuing pan is
      // most likely to be contained by.
      while (entries.length > maxEntries) entries.shift();
    },

    get size() {
      return entries.length;
    },
  };
}
