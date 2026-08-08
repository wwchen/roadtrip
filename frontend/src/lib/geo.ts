// Pure geometry helpers. Typed port of the geometry section of web/core.js —
// behavior preserved exactly.
//
// Split out of core.js because core.js also owns the mutable `state` singleton
// and the MapLibre popup, neither of which survives the migration: `state`
// becomes mapStore and the popup becomes a React-owned overlay. These four
// functions are pure and move as-is.

/** `[[west, south], [east, north]]` — the bbox order core.js and the API use. */
export type Bbox = [[number, number], [number, number]];

/** `[centerLng, centerLat, bbox]`, as returned by `geomCenter`. */
export type GeomCenter = [number, number, Bbox];

/** A GeoJSON geometry, kept structurally loose — see `geomCenter`. */
export interface GeoJsonGeometry {
  type?: string;
  coordinates?: unknown;
  geometries?: GeoJsonGeometry[];
}

const EARTH_RADIUS_KM = 6371;

/** Metres-away threshold: below 1 km the label switches to metres. */
const METRES_LABEL_BELOW_KM = 1;
/** Below this the label keeps one decimal; above it rounds to whole km. */
const ONE_DECIMAL_BELOW_KM = 10;

// Degree span → zoom, coarse buckets. Tuned by eye for the park/campground
// footprints this app flies to; not a projection calculation.
const ZOOM_BY_SPAN: ReadonlyArray<readonly [spanDegrees: number, zoom: number]> = [
  [3, 7],
  [1, 8.5],
  [0.3, 10],
  [0.1, 11],
];
const ZOOM_FOR_SMALLEST_SPAN = 12;

const toRadians = (degrees: number): number => (degrees * Math.PI) / 180;

/** Haversine distance in km. Used for distance-from-me and sort-by-nearest. */
export function distanceKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
}

export function formatDistance(km: number): string {
  if (km < METRES_LABEL_BELOW_KM) return Math.round(km * 1000) + ' m away';
  if (km < ONE_DECIMAL_BELOW_KM) return km.toFixed(1) + ' km away';
  return Math.round(km) + ' km away';
}

/**
 * Rough centroid of any GeoJSON geometry via bbox midpoint — good enough for
 * `flyTo`, and far cheaper than a true centroid.
 *
 * Handles Point/LineString/Polygon/MultiPolygon/MultiLineString/MultiPoint by
 * recursive coordinate descent, and GeometryCollection via its `geometries`
 * array (PAD-US ships some parks as GeometryCollection with mixed polygon
 * parts). An empty or missing geometry yields the origin rather than NaNs, so a
 * caller never flies the map to nowhere.
 */
export function geomCenter(geom: GeoJsonGeometry | null | undefined): GeomCenter {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  const visit = (c: unknown): void => {
    if (!Array.isArray(c) || c.length === 0) return;
    if (typeof c[0] === 'number') {
      const [x, y] = c as number[];
      if (x! < minX) minX = x!;
      if (x! > maxX) maxX = x!;
      if (y! < minY) minY = y!;
      if (y! > maxY) maxY = y!;
    } else {
      for (const x of c) visit(x);
    }
  };

  if (geom?.type === 'GeometryCollection') {
    for (const g of geom.geometries || []) visit(g.coordinates);
  } else {
    visit(geom?.coordinates);
  }

  if (!isFinite(minX)) {
    return [
      0,
      0,
      [
        [0, 0],
        [0, 0],
      ],
    ];
  }
  return [
    (minX + maxX) / 2,
    (minY + maxY) / 2,
    [
      [minX, minY],
      [maxX, maxY],
    ],
  ];
}

export function zoomForBbox(bbox: Bbox): number {
  const [[w, s], [e, n]] = bbox;
  const span = Math.max(e - w, n - s);
  for (const [threshold, zoom] of ZOOM_BY_SPAN) {
    if (span > threshold) return zoom;
  }
  return ZOOM_FOR_SMALLEST_SPAN;
}
