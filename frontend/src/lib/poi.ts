// POI hydration flattening.
//
// Runs after a `/api/pois/{id}` hydration. Promotes the rich nested structure
// (`detail`, `raw`, `address`, provider payloads) into the flat property names
// the popups, drawer, and campground card read directly, so rendering code never
// has to know which shape a POI arrived in.
//
// Re-flattening is a no-op for the address, name, and campground paths, and the
// tests pin that. It is NOT a no-op for the fields derived solely from `raw`
// with no flat fallback, because the first pass consumes `raw` and deletes it:
// a park's `Loc_Nm`/`GIS_Acres`/`Mang_Name` and a supercharger's
// `stallCount`/`powerKilowatt` reset on a second pass. core.js's blanket
// "Idempotent" note overstated this; the behavior is carried over byte-for-byte
// (a parity suite pins it against the original) and only the claim is
// corrected. Call it once per hydration.
//
// The provider-specific branches at the bottom are deliberately kept as-is
// rather than reshaped into a registry. They encode which upstream field names
// each vendor actually ships, they are the highest-risk part of this port, and
// Phase 4 rewrites their consumers anyway — a behavior-faithful move now keeps
// the diff reviewable against the original.
import { token } from '@tokens';

const SUPERCHARGER_COLOR_TOKEN = '--rt-layer-supercharger';
const DEFAULT_SUPERCHARGER_STATUS = 'OPEN';

const CATEGORY_CAMPGROUND = 'campground';
const CATEGORY_NATIONAL_PARK = 'national-park';
const CATEGORY_STATE_PARK = 'state-park';
const SUPERCHARGER_CATEGORIES = ['tesla_supercharger', 'supercharger'];

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

  if (p.category === CATEGORY_CAMPGROUND) {
    promoteCanonicalCampgroundFields(flat, p, raw);
  }

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
  if (SUPERCHARGER_CATEGORIES.includes(p.category as string)) {
    promoteSuperchargerFields(flat, p, raw);
  }
  flat.name = p.name || raw.name || flat.name;
  return { ...f, properties: flat };
}

function promoteSuperchargerFields(flat: Props, p: Props, raw: Props): void {
  const detailPayload = objectValue(
    raw.detail_payload,
    raw.detailPayload,
    flat.detail_payload,
    flat.detailPayload,
  );
  const indexPayload = objectValue(
    raw.index_payload,
    raw.indexPayload,
    flat.index_payload,
    flat.indexPayload,
  );
  const availabilityProfile = objectValue(
    raw.availability_profile,
    raw.availabilityProfile,
    flat.availability_profile,
    flat.availabilityProfile,
    detailPayload?.availabilityProfile,
  );
  const amenities = arrayValue(raw.amenities, flat.amenities, detailPayload?.amenities);
  const upstreamDetail: Props = { ...(detailPayload || {}) };
  if (availabilityProfile) upstreamDetail.availabilityProfile = availabilityProfile;
  const timeZone = firstText(
    raw.time_zone,
    raw.timeZone,
    flat.time_zone,
    flat.timeZone,
    detailPayload?.timeZone,
  );
  if (timeZone) upstreamDetail.timeZone = timeZone;
  if (amenities) upstreamDetail.amenities = amenities;
  const accessHours = objectValue(upstreamDetail.accessHours) || {};
  const twentyFourSeven = firstPresent(
    raw.twenty_four_seven,
    flat.twenty_four_seven,
    objectValue(detailPayload?.accessHours)?.twentyFourSeven,
  );
  if (twentyFourSeven !== undefined) {
    upstreamDetail.accessHours = { ...accessHours, twentyFourSeven };
  }
  const openToNonTeslas = firstPresent(
    raw.open_to_non_teslas,
    flat.open_to_non_teslas,
    detailPayload?.openToNonTeslas,
  );
  if (openToNonTeslas !== undefined) upstreamDetail.openToNonTeslas = openToNonTeslas;
  const trailerFriendly = firstPresent(
    raw.trailer_friendly,
    flat.trailer_friendly,
    detailPayload?.isTrailerFriendly,
  );
  if (trailerFriendly !== undefined) upstreamDetail.isTrailerFriendly = trailerFriendly;

  flat.locationId = p.source_id || raw.location_slug || flat.location_slug;
  flat.stallCount = raw.stall_count ?? 0;
  flat.powerKilowatt = raw.max_power_kw ?? 0;
  flat.color = raw.color || token(SUPERCHARGER_COLOR_TOKEN);
  flat.status =
    firstText(
      raw.status,
      raw.site_status,
      objectValue(indexPayload?.supercharger_function)?.site_status,
    ) || DEFAULT_SUPERCHARGER_STATUS;
  flat.pricebooks = raw.pricebooks || [];
  flat.timeZone = timeZone;
  flat.availabilityProfile = availabilityProfile || null;
  flat.detailPayload = detailPayload || {};
  flat.upstream = {
    ...(objectValue(p.upstream, flat.upstream) || {}),
    ...(indexPayload ? { index: indexPayload } : {}),
    detail: upstreamDetail,
  };
}

function promoteCanonicalCampgroundFields(flat: Props, p: Props, raw: Props): void {
  const management = (raw.management && typeof raw.management === 'object' ? raw.management : {}) as Props;
  const contact = (raw.contact && typeof raw.contact === 'object' ? raw.contact : {}) as Props;
  const location = (raw.location && typeof raw.location === 'object' ? raw.location : {}) as Props;
  const metadata = (raw.metadata && typeof raw.metadata === 'object' ? raw.metadata : {}) as Props;

  flat.description = firstText(p.description, flat.description, raw.description);
  flat.photo_url = firstText(p.photo_url, flat.photo_url, campgroundPhotoUrl(raw.photos));
  flat.agency = firstText(
    p.agency,
    flat.agency,
    management.agency_name,
    management.agency,
    management.name,
  );
  flat.phone = firstText(p.phone, flat.phone, contact.primary_phone, contact.phone);
  flat.email = firstText(p.email, contact.email, contact.primary_email);
  flat.reserve_url = firstText(p.reserve_url, flat.reserve_url, raw.reservation_url);
  flat.status = firstText(p.status, flat.status, raw.status);
  flat.status_description = firstText(p.status_description, flat.status_description);
  flat.kind = firstText(p.kind, flat.kind, raw.kind);
  flat.price = p.price ?? raw.price ?? flat.price;
  flat.schedule = p.schedule ?? raw.default_campsite_schedule ?? flat.schedule;
  flat.default_campsite_schedule = flat.schedule;
  flat.amenities = p.amenities ?? raw.amenities ?? flat.amenities;
  flat.cell_coverage = p.cell_coverage ?? raw.cell_service ?? flat.cell_coverage;
  flat.last_verified = firstText(p.last_verified, metadata.last_updated, raw.updated_at);
  flat.max_rv_length = p.max_rv_length ?? raw.max_rv_length ?? flat.max_rv_length;
  flat.max_trailer_length = p.max_trailer_length ?? raw.max_trailer_length ?? flat.max_trailer_length;
  flat.has_pull_through_sites =
    p.has_pull_through_sites ?? raw.has_pull_through_sites ?? flat.has_pull_through_sites;
  flat.big_rig_friendly = p.big_rig_friendly ?? raw.big_rig_friendly ?? flat.big_rig_friendly;
  flat.elevation = p.elevation ?? location.elevation ?? flat.elevation;
  flat.management = p.management ?? raw.management ?? flat.management;
  flat.contact = p.contact ?? raw.contact ?? flat.contact;
  flat.links = p.links ?? raw.links ?? flat.links;
  flat.alerts = p.alerts ?? raw.alerts ?? flat.alerts;
  flat.connections = p.connections ?? raw.connections ?? flat.connections;
  flat.metadata = p.metadata ?? raw.metadata ?? flat.metadata;
}

function campgroundPhotoUrl(photos: unknown): string {
  if (!Array.isArray(photos)) return '';
  for (const photo of photos) {
    if (!photo || typeof photo !== 'object') continue;
    const p = photo as Props;
    const url = firstText(p.large_url, p.medium_url, p.small_url, p.original_url);
    if (url) return url;
  }
  return '';
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

/** First value that is neither `undefined` nor `null`. */
function firstPresent(...values: unknown[]): unknown {
  for (const value of values) {
    if (value !== undefined && value !== null) return value;
  }
  return undefined;
}

/** First plain-object value (arrays excluded); `null` when there is none. */
function objectValue(...values: unknown[]): Props | null {
  for (const value of values) {
    if (value && typeof value === 'object' && !Array.isArray(value)) return value as Props;
  }
  return null;
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

function arrayValue(...values: unknown[]): unknown[] | null {
  for (const value of values) {
    if (Array.isArray(value)) return value;
  }
  return null;
}
