// How far along the route a point sits.
//
// Campgrounds are listed in the order a driver meets them, which is the
// only ordering that makes the card list useful: sorting by straight-line distance
// from the origin puts a campground 20km off the far end of the route above one the
// driver passes in the first hour.
//
// Pure and index-first, as the original was: building the cumulative table once per
// route turns each card's lookup into a single scan instead of a re-measure.
import { distanceKm } from '@/lib/geo';
import type { LineString } from 'geojson';

export interface RouteIndex {
  /** The route's coordinates, as given. */
  coords: readonly (readonly number[])[];
  /** `cum[i]` is the distance in km from the start to vertex `i`. */
  cum: Float64Array;
}

/** Build the cumulative-distance table for a route line, or null if there is none. */
export function buildRouteIndex(line: LineString | null | undefined): RouteIndex | null {
  const coords = line?.coordinates;
  if (!coords?.length) return null;
  const cum = new Float64Array(coords.length);
  for (let i = 1; i < coords.length; i += 1) {
    const [lng1, lat1] = coords[i - 1] as [number, number];
    const [lng2, lat2] = coords[i] as [number, number];
    cum[i] = (cum[i - 1] ?? 0) + distanceKm(lat1, lng1, lat2, lng2);
  }
  return { coords, cum };
}

/**
 * Project a point onto the indexed route and answer how far along it lands, in km.
 *
 * Two steps, and the split is deliberate: the *closest segment* is found in degree
 * space, which treats each segment as flat — fine, because it only has to pick a
 * winner between segments — and the *distance* then comes from the cumulative table,
 * which was built with real great-circle lengths. So the answer is accurate even
 * though the search is cheap.
 *
 * Returns 0 for a point with no index to project onto, which is what the vanilla did:
 * the card list is sorted by this value, and an unindexed route has no order to
 * impose anyway.
 */
export function distanceAlongRouteKm(
  index: RouteIndex | null,
  lng: number,
  lat: number,
): number {
  if (!index) return 0;
  const { coords, cum } = index;
  let bestSegment = 0;
  let bestT = 0;
  let bestSquaredDistance = Infinity;

  for (let i = 0; i < coords.length - 1; i += 1) {
    const [ax, ay] = coords[i] as [number, number];
    const [bx, by] = coords[i + 1] as [number, number];
    const dx = bx - ax;
    const dy = by - ay;
    const lengthSquared = dx * dx + dy * dy;
    // A zero-length segment (a duplicated vertex, which routing engines do emit)
    // projects to its own start rather than dividing by zero.
    let t = lengthSquared ? ((lng - ax) * dx + (lat - ay) * dy) / lengthSquared : 0;
    if (t < 0) t = 0;
    else if (t > 1) t = 1;
    const ex = lng - (ax + t * dx);
    const ey = lat - (ay + t * dy);
    const squaredDistance = ex * ex + ey * ey;
    if (squaredDistance < bestSquaredDistance) {
      bestSquaredDistance = squaredDistance;
      bestSegment = i;
      bestT = t;
    }
  }

  const start = cum[bestSegment] ?? 0;
  const segmentLength = (cum[bestSegment + 1] ?? start) - start;
  return start + bestT * segmentLength;
}
