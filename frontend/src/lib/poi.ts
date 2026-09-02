// POI hydration flattening.
//
// Runs after a `/api/pois/{id}` hydration. Promotes the rich nested structure
// (`detail`, `raw`, `address`, provider payloads) into the flat property names
// the popups, drawer, and campground card read directly, so rendering code never
// has to know which shape a POI arrived in.
//
// Re-flattening is a no-op for the address, name, campground, and supercharger
// paths, and the tests pin that. It is NOT a no-op for the fields derived
// solely from `raw` with no flat fallback, because the first pass consumes
// `raw` and deletes it: a park's `Loc_Nm`/`GIS_Acres`/`Mang_Name` reset on a
// second pass. core.js's blanket "Idempotent" note overstated this; the
// behavior is carried over byte-for-byte (a parity suite pins it against the
// original) and only the claim is corrected. Call it once per hydration.
//
// The provider-specific branches at the bottom are deliberately kept as-is
// rather than reshaped into a registry. They encode which upstream field names
// each vendor actually ships, they are the highest-risk part of this port, and
// Phase 4 rewrites their consumers anyway — a behavior-faithful move now keeps
// the diff reviewable against the original.

const CATEGORY_CAMPGROUND = 'campground';
const CATEGORY_NATIONAL_PARK = 'national-park';
const CATEGORY_STATE_PARK = 'state-park';

type Props = Record<string, unknown>;

/**
 * A POI feature as it arrives from the API — GeoJSON-shaped, but with
 * `properties` intentionally open: the whole point of this module is that the
 * nested shape varies by provider and category.
 */
export interface PoiFeature {
  id?: string | number;
  properties?: Props | null;
  [key: string]: unknown;
}

/** A POI feature whose `properties` have been flattened. */
export interface FlatPoiFeature extends PoiFeature {
  properties: Props;
}

export function flattenHydratedPoi(f: PoiFeature): FlatPoiFeature {
  const p: Props = f.properties || {};
  const detail = parseObject(p.detail) || {};
  const raw = parseObject(p.raw) || parseObject(detail.raw) || {};
  const detailProps: Props = { ...detail };
  delete detailProps.raw;
  const flat: Props =
    p.category === CATEGORY_CAMPGROUND
      ? {
          id: f.id,
          ...p,
          ...detailProps,
          upstream: p.upstream || detailProps.upstream,
        }
      : { id: f.id, ...raw, ...p, ...detailProps };
  delete flat.detail;
  delete flat.raw;

  // Address arrives as a nested object from /api/pois/{id} (the JSONB column).
  // Flatten its parts onto the top of properties for every category that
  // surfaces an address — popups read them directly.
  const addr = (flat.address || {}) as Props;
  const nestedAddr = (
    addr.address && typeof addr.address === 'object' ? addr.address : {}
  ) as Props;
  flat.full_address = firstText(addr.full, nestedAddr.full, flat.full_address);
  flat.street = firstText(
    addr.street,
    addr.street1,
    addr.address_line,
    nestedAddr.street,
    nestedAddr.street1,
    nestedAddr.address_line,
  );
  flat.city = firstText(addr.city, nestedAddr.city);
  flat.state = firstText(
    addr.state,
    addr.state_code,
    nestedAddr.state,
    nestedAddr.state_code,
    p.region,
  );
  flat.country = firstText(
    p.country,
    addr.country,
    addr.country_code,
    nestedAddr.country,
    nestedAddr.country_code,
    flat.country,
  );
  flat.postcode = firstText(
    addr.postcode,
    addr.postal_code,
    addr.zipcode,
    nestedAddr.postcode,
    nestedAddr.postal_code,
    nestedAddr.zipcode,
  );

  // info_url is the BE's canonical "open this in upstream" link (Tesla findus,
  // planetfitness.com gym page, BC Parks page, …). Popups read p.website /
  // p.infoUrl — keep both names alive.
  flat.website = firstText(flat.info_url, p.info_url, p.website, flat.website);
  flat.infoUrl = firstText(flat.info_url, p.info_url);

  if (p.category === CATEGORY_CAMPGROUND && p.subcategory) {
    flat.category = p.subcategory;
  }
  if (p.category === CATEGORY_NATIONAL_PARK || p.category === CATEGORY_STATE_PARK) {
    // Park layers + popups read Unit_Nm / Loc_Nm / State_Nm / GIS_Acres /
    // Mang_Name — the field names PAD-US used. The new ETL stores the facts
    // under different keys (acres, official_name, designation, region, source);
    // map them here so the rendering code stays put.
    flat.Unit_Nm = raw.Unit_Nm || p.unit_name || p.name;
    flat.State_Nm = raw.State_Nm || p.region || '';
    flat.Loc_Nm = raw.Loc_Nm || raw.official_name || '';
    flat.GIS_Acres = raw.GIS_Acres ?? raw.acres ?? null;
    flat.Mang_Name = raw.Mang_Name || raw.designation || '';
  }
  flat.name = p.name || raw.name || flat.name;
  return { ...f, properties: flat };
}

/** First non-blank string, trimmed; `''` when there is none. */
function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value !== 'string') continue;
    const trimmed = value.trim();
    if (trimmed) return trimmed;
  }
  return '';
}

/**
 * A plain object, parsing a JSON string if that is what arrived. `detail` and
 * `raw` come back as JSONB columns and are sometimes already objects, sometimes
 * encoded strings.
 */
function parseObject(value: unknown): Props | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value as Props;
  if (typeof value !== 'string' || value.trim() === '') return null;
  try {
    const parsed: unknown = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Props) : null;
  } catch {
    return null;
  }
}
