// Everything the campground drawer reasons about, as data.
//
// Functions return structured values — a detail row is
// `{ label, value }` where the value is text, a link, or chips — and the component
// decides how to render.
//
// Provider markup (fees, directions, description) is the one exception and does not
// live here: it goes through `lib/upstream-html.ts`, which is the only sanctioned
// `dangerouslySetInnerHTML` path.

import type { AlertDto, CampgroundDetail, PriceDto } from '@/api/poi-api';

/** POI properties after `flattenHydratedPoi`, which is deliberately open. */
type Props = Record<string, unknown>;

/**
 * The half of the bag the backend has taken ownership of.
 *
 * `flattenHydratedPoi` is open on purpose — most of a hydrated POI is still
 * whatever the vendor sent — but the fields on `CampgroundDetail` are
 * `PoiCategoryDetailSchema`'s own, typed and labelled server-side. This is the
 * single place that assertion is made; every reader below goes through it
 * rather than indexing the open bag, so a wire rename is a typecheck failure in
 * one file instead of a row that silently stops rendering.
 */
const typed = (p: Props): Partial<CampgroundDetail> => p as Partial<CampgroundDetail>;

// ---------------------------------------------------------------------------
// Small readers, ported verbatim in behaviour
// ---------------------------------------------------------------------------

/** The first non-empty string or number, trimmed. The original's `firstText`. */
export function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value !== 'string' && typeof value !== 'number') continue;
    const trimmed = String(value).trim();
    if (trimmed) return trimmed;
  }
  return '';
}

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function formatNumber(n: number): string {
  return n.toLocaleString('en-US', { maximumFractionDigits: Number.isInteger(n) ? 0 : 2 });
}

/** `snake_case` or `Sentence case` → `Title Case`. */
export function titleCase(value: unknown): string {
  return String(value)
    .replace(/_/g, ' ')
    .replace(/\w\S*/g, (word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase());
}

/** A URL safe to put in an href, or ''. Mirrors the sanitiser's scheme rule. */
export function safeUrl(url: unknown): string {
  const value = firstText(url);
  return value && /^(https?:|mailto:|tel:|\/|#)/i.test(value) ? value : '';
}

export function urlHost(url: unknown): string {
  try {
    return new URL(String(url)).hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
}

// ---------------------------------------------------------------------------
// Amenities and activities
// ---------------------------------------------------------------------------

/** An amenity, and whether the label states its absence ("No showers"). */
export interface AmenityTag {
  label: string;
  absent: boolean;
}

/**
 * The at-a-glance amenity chips.
 *
 * The words are the backend's: `label` is render-ready, already "No water" for an
 * absence and "Vault toilets" for a detailed toilets entry. `present` survives
 * only because the chip gives an absence a hue, and a label that reads "No water"
 * has flattened that distinction into prose.
 */
export function amenityTags(p: Props): AmenityTag[] {
  return (typed(p).amenities ?? []).map((amenity) => ({
    label: amenity.label,
    absent: !amenity.present,
  }));
}

export function activityList(p: Props): string[] {
  return typed(p).activities ?? [];
}

// ---------------------------------------------------------------------------
// Cell coverage
// ---------------------------------------------------------------------------

/** rec.gov reports signal on a 0-4 scale; the bucket drives the chip colour. */
const MAX_SIGNAL_BUCKET = 4;

export interface CarrierSignal {
  carrier: string;
  label: string;
  /** Average signal, 0-4. */
  avg: number;
  /** Report count, when the provider sent one. */
  count: number | null;
  /** Rounded and clamped, for the `data-bucket` colour. */
  bucket: number;
}

/**
 * Per-carrier signal, strongest first.
 *
 * The carrier's display name arrives on the wire; the ordering and the bucket are
 * this page's, because both are about how a row of bars reads rather than what the
 * provider measured.
 */
export function carrierSignals(p: Props): CarrierSignal[] {
  return (typed(p).cell_coverage ?? [])
    .map((signal) => ({
      carrier: signal.carrier,
      label: signal.label,
      avg: signal.average,
      count: signal.count ?? null,
      bucket: Math.max(0, Math.min(MAX_SIGNAL_BUCKET, Math.round(signal.average))),
    }))
    .sort((a, b) => b.avg - a.avg);
}

// ---------------------------------------------------------------------------
// Rating
// ---------------------------------------------------------------------------

export interface Rating {
  average: number;
  count: number;
  /** `★★★★☆`, rounded to the nearest star. */
  stars: string;
}

export function rating(p: Props): Rating | null {
  const value = typed(p).rating;
  if (!value) return null;
  return { average: value.average, count: value.count, stars: stars(value.average) };
}

/** Half-stars round to the nearest whole, as the original did. */
export function stars(value: number): string {
  const full = Math.round(value);
  return '★'.repeat(Math.max(0, full)) + '☆'.repeat(Math.max(0, 5 - full));
}

// ---------------------------------------------------------------------------
// Freshness
// ---------------------------------------------------------------------------

/** Past this, the footer warns and suggests checking before booking. */
export const STALE_AFTER_DAYS = 60;
const MS_PER_DAY = 86_400_000;

export interface Verified {
  date: string;
  stale: boolean;
}

export function verified(p: Props, now: Date = new Date()): Verified | null {
  const raw = typed(p).last_verified;
  if (!raw?.trim()) return null;
  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) return null;
  return {
    date: verifiedLabel(parsed, now),
    stale: (now.getTime() - parsed.getTime()) / MS_PER_DAY > STALE_AFTER_DAYS,
  };
}

/**
 * "23 May", or "23 May 2024" once the year stops being obvious.
 *
 * The field arrives as whatever the provider stored — a bare `2026-08-01` from
 * one, a full `2026-05-06T23:47:29Z` from another — and this used to render that
 * string verbatim. A second of a timestamp is not freshness, and the two shapes
 * side by side read as two different kinds of fact; the stamp is one line in the
 * footer, so it says the day and stops.
 *
 * The year is dropped only for the current one. A date from two years ago shown
 * as "6 May" reads as this spring, which is exactly the wrong impression for a
 * value whose whole job is to say how much to trust the page.
 */
function verifiedLabel(parsed: Date, now: Date): string {
  const sameYear = parsed.getUTCFullYear() === now.getUTCFullYear();
  return parsed.toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    ...(sameYear ? null : { year: 'numeric' }),
    timeZone: 'UTC',
  });
}

// ---------------------------------------------------------------------------
// Calls to action
// ---------------------------------------------------------------------------

export interface Cta {
  url: string;
  label: string;
  variant: 'primary' | 'secondary';
}

/**
 * The buttons a campground gets.
 *
 * The backend computes an ordered CTA list per pin (`booking_ref` + `info_url` →
 * vendor URLs and labels, including dated Aspira deeplinks), and the client renders it
 * verbatim — per-vendor URL precedence is a server concern. Everything below the first
 * branch is fallback for pins the backend could not resolve.
 */
export function campgroundCtas(p: Props): Cta[] {
  const provided = normalizeCtas(p.cta)
    .map((cta, index) => toCta(cta, index))
    .filter((cta): cta is Cta => cta !== null);
  if (provided.length > 0) return provided;

  const reserveUrl = safeUrl(firstText(p.reserve_url, p.reservation_url));
  if (reserveUrl) return [{ url: reserveUrl, label: reserveLabel(reserveUrl), variant: 'primary' }];

  const infoUrl = safeUrl(firstText(p.info_url, p.website));
  if (infoUrl) return [{ url: infoUrl, label: 'Visit website', variant: 'primary' }];

  // Best-effort name search. A park system's own search beats Google because its
  // results are already park entries rather than a noisy web search.
  const regional = regionalParkSearch(p);
  if (regional) return [{ ...regional, variant: 'primary' }];

  const query = encodeURIComponent(`${firstText(p.name)} ${firstText(p.state)}`.trim());
  return [
    {
      url: `https://www.google.com/search?q=${query}+campground`,
      label: 'Search Google',
      variant: 'primary',
    },
  ];
}

function normalizeCtas(cta: unknown): Record<string, unknown>[] {
  if (Array.isArray(cta)) {
    return cta.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object');
  }
  if (cta && typeof cta === 'object') return [cta as Record<string, unknown>];
  return [];
}

function toCta(cta: Record<string, unknown>, index: number): Cta | null {
  const url = safeUrl(cta.url);
  if (!url) return null;
  return {
    url,
    label: firstText(cta.label, cta.title, cta.name, url),
    // Only the first CTA is the primary action; the rest are alternates.
    variant: index === 0 ? 'primary' : 'secondary',
  };
}

/** Name the destination, so "Reserve" is not a mystery link. */
function reserveLabel(url: string): string {
  const host = urlHost(url);
  if (host.endsWith('recreation.gov')) return 'View on recreation.gov';
  if (host.endsWith('reserveamerica.com')) return 'View on ReserveAmerica';
  if (host.endsWith('reservecalifornia.com')) return 'View on ReserveCalifornia';
  if (host.endsWith('parks.canada.ca') || host.endsWith('pc.gc.ca')) return 'View on Parks Canada';
  return 'Reserve';
}

/**
 * A park system's own search page for this pin's region, or null.
 *
 * Only systems with a working search-by-name URL are listed; returning null for the
 * rest leaves the Google fallback in place, which is the honest outcome.
 */
function regionalParkSearch(p: Props): { url: string; label: string } | null {
  const query = encodeURIComponent(firstText(p.name));
  const state = firstText(p.state);

  if (firstText(p.country) === 'CA') {
    if (state === 'AB') {
      return {
        url: `https://www.albertaparks.ca/parks/?searchPhrase=${query}`,
        label: 'Search Alberta Parks',
      };
    }
    if (state === 'BC') return { url: `https://bcparks.ca/?s=${query}`, label: 'Search BC Parks' };
  }

  const US_PARK_SEARCHES = new Map<string, { url: string; label: string }>([
    [
      'WA',
      {
        url: `https://parks.wa.gov/find-parks/parks-and-recreation-areas?keyword=${query}`,
        label: 'Search WA State Parks',
      },
    ],
    [
      'OR',
      {
        url: `https://stateparks.oregon.gov/index.cfm?do=search.results&searchTerm=${query}`,
        label: 'Search OR State Parks',
      },
    ],
    ['CA', { url: `https://www.parks.ca.gov/?page_id=21805&q=${query}`, label: 'Search CA State Parks' }],
    [
      'CO',
      {
        url: `https://cpw.state.co.us/buyapply/Pages/CampingDetails.aspx?q=${query}`,
        label: 'Search CO Parks',
      },
    ],
    [
      'TX',
      {
        url: `https://tpwd.texas.gov/state-parks/find-a-park?keyword=${query}`,
        label: 'Search TX State Parks',
      },
    ],
    ['NY', { url: `https://parks.ny.gov/parks/?q=${query}`, label: 'Search NY State Parks' }],
    [
      'FL',
      {
        url: `https://www.floridastateparks.org/parks-and-trails?keyword=${query}`,
        label: 'Search FL State Parks',
      },
    ],
  ]);

  return US_PARK_SEARCHES.get(state) ?? null;
}

// ---------------------------------------------------------------------------
// Structured details
// ---------------------------------------------------------------------------

/** A detail value: plain text, a link, or the connections chip row. */
export type DetailValue =
  | { kind: 'text'; text: string }
  | { kind: 'link'; href: string; label: string }
  | { kind: 'chips'; chips: { key: string; value: string }[] };

export interface DetailRow {
  label: string;
  value: DetailValue;
}

export interface DetailGroup {
  title: string;
  rows: DetailRow[];
}

export interface CampgroundLink {
  href: string;
  label: string;
}

export interface CampgroundAlert {
  title: string;
  body: string;
}

export interface StructuredDetails {
  groups: DetailGroup[];
  links: CampgroundLink[];
  alerts: CampgroundAlert[];
}

/**
 * The group titles, as constants: the POI page maps each of these onto one of its
 * blocks (stay details, contact, provenance), so the strings are a contract
 * between this module and `types/campground.tsx` rather than three labels.
 */
export const STAY_DETAILS_GROUP = 'Stay details';
export const CONTACT_GROUP = 'Contact';
export const SOURCE_GROUP = 'Source metadata';

/**
 * The "More details" body, as groups of rows.
 *
 * Empty rows and empty groups drop out, so a sparse pin renders a short section
 * rather than a grid of blanks — and `hasDetails` below is how the drawer knows
 * whether to render the accordion at all.
 */
export function structuredDetails(p: Props): StructuredDetails {
  const detail = typed(p);
  const stay: DetailRow[] = rows([
    ['Status', text(firstText(p.status_description, p.status ? titleCase(p.status) : ''))],
    ['Price', text(priceRange(detail.price))],
    ['Check-in', text(clockTime(detail.schedule?.check_in))],
    ['Check-out', text(clockTime(detail.schedule?.check_out))],
    ['Max RV', text(feet(p.max_rv_length))],
    ['Max trailer', text(feet(p.max_trailer_length))],
    ['Pull-through', text(yesNo(p.has_pull_through_sites))],
    ['Big-rig friendly', text(yesNo(p.big_rig_friendly))],
    ['Elevation', text(feet(p.elevation))],
  ]);

  const contact: DetailRow[] = rows([
    ['Address', text(address(p))],
    ['Phone', text(firstText(p.phone))],
    ['Email', email(p.email)],
    ['Managed by', management(p)],
  ]);

  const source: DetailRow[] = rows([
    ['Data source', text(firstText(sourcesLabel(p.sources), p.source))],
    ['Source ID', text(firstText(p.source_id))],
    ['Availability provider', text(firstText(p.availability_provider, p.provider_source))],
    [
      'Booking site',
      text(firstText(p.booking_site, urlHost(firstText(p.reserve_url, p.reservation_url)))),
    ],
    ['Last updated', text(firstText(detail.last_verified))],
    ['Connections', connections(p.connections)],
  ]);

  return {
    groups: [
      { title: STAY_DETAILS_GROUP, rows: stay },
      { title: CONTACT_GROUP, rows: contact },
      { title: SOURCE_GROUP, rows: source },
    ].filter((group) => group.rows.length > 0),
    links: campgroundLinks(p.links),
    alerts: campgroundAlerts(detail.alerts),
  };
}

/** Whether the accordion has anything in it. */
export function hasDetails(details: StructuredDetails): boolean {
  return details.groups.length > 0 || details.links.length > 0 || details.alerts.length > 0;
}

const text = (value: string): DetailValue => ({ kind: 'text', text: value });

/** Drop rows whose value came back empty. */
function rows(entries: [string, DetailValue | null][]): DetailRow[] {
  const out: DetailRow[] = [];
  for (const [label, value] of entries) {
    if (!value) continue;
    if (value.kind === 'text' && !value.text) continue;
    if (value.kind === 'chips' && value.chips.length === 0) continue;
    out.push({ label, value });
  }
  return out;
}

function email(value: unknown): DetailValue | null {
  const address = firstText(value);
  return address ? { kind: 'link', href: `mailto:${address}`, label: address } : null;
}

function management(p: Props): DetailValue | null {
  const m = (p.management && typeof p.management === 'object' ? p.management : {}) as Props;
  const name = firstText(p.agency, m.agency_name, m.agency, m.name);
  const url = safeUrl(firstText(m.agency_website, m.website_url, m.website, m.url));
  if (!name && !url) return null;
  if (!url) return text(name);
  return { kind: 'link', href: url, label: name || url };
}

/**
 * A postal address from whichever of the nested shapes the provider used.
 *
 * The flattener promotes what it can, but `address` can still be an object with its
 * own nested `address`, so both levels are read — matching the original's fallback
 * chain exactly.
 */
function address(p: Props): string {
  const addr = (p.address && typeof p.address === 'object' ? p.address : {}) as Props;
  const nested = (addr.address && typeof addr.address === 'object' ? addr.address : {}) as Props;

  const full = firstText(p.full_address, addr.full, nested.full);
  if (full) return full;

  const street = firstText(
    p.street,
    addr.street,
    addr.street1,
    addr.address_line,
    nested.street,
    nested.street1,
    nested.address_line,
  );
  const city = firstText(p.city, addr.city, nested.city);
  const region = firstText(p.state, addr.state, addr.state_code, nested.state, nested.state_code);
  const postcode = firstText(
    p.postcode,
    addr.postcode,
    addr.postal_code,
    addr.zipcode,
    nested.postcode,
    nested.postal_code,
    nested.zipcode,
  );
  const country = firstText(
    p.country,
    addr.country,
    addr.country_code,
    nested.country,
    nested.country_code,
  );

  const locality = [city, region, postcode].filter(Boolean).join(', ');
  return [street, locality, country].filter(Boolean).join(' · ');
}

function connections(value: unknown): DetailValue | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const chips = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== null && v !== undefined && String(v).trim())
    .map(([key, v]) => ({ key, value: String(v) }));
  return chips.length > 0 ? { kind: 'chips', chips } : null;
}

function campgroundLinks(value: unknown): CampgroundLink[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((link) => {
      if (!link || typeof link !== 'object') return null;
      const record = link as Record<string, unknown>;
      const href = safeUrl(firstText(record.url, record.href));
      if (!href) return null;
      return { href, label: firstText(record.title, record.label, record.name, href) };
    })
    .filter((link): link is CampgroundLink => link !== null);
}

/**
 * The notices the "Good to know" block prints.
 *
 * `ends_on` and `source_url` are on the wire and deliberately not rendered: the
 * block is prose, and a date the page cannot act on reads as clutter next to the
 * closure it qualifies.
 */
function campgroundAlerts(alerts: AlertDto[] | undefined): CampgroundAlert[] {
  return (alerts ?? [])
    .map((alert) => ({ title: firstText(alert.title), body: firstText(alert.body) }))
    .filter((alert) => alert.title !== '' || alert.body !== '');
}

function sourcesLabel(sources: unknown): string {
  if (!Array.isArray(sources)) return '';
  const cleaned = sources
    .map((v) => (typeof v === 'string' || typeof v === 'number' ? String(v).trim() : ''))
    .filter(Boolean);
  // Deduped: a POI merged from two feeds of the same vendor lists it once.
  return [...new Set(cleaned)].join(', ');
}

/** The currencies whose symbol a North American reader reads without being told. */
const SYMBOL_CURRENCIES = ['USD', 'CAD'];
const DEFAULT_CURRENCY = 'USD';

/** `$25`, `$25-$40`, or `CAD 30` for currencies with no familiar symbol. */
function priceRange(price: PriceDto | undefined): string {
  const min = finiteNumber(price?.minimum);
  const max = finiteNumber(price?.maximum);
  if (min == null && max == null) return '';

  const currency = (firstText(price?.currency) || DEFAULT_CURRENCY).toUpperCase();
  const symbol = SYMBOL_CURRENCIES.includes(currency) ? '$' : `${currency} `;
  const format = (n: number) => `${symbol}${formatNumber(n)}`;

  if (min != null && max != null && min !== max) return `${format(min)}-${format(max)}`;
  return format((min ?? max) as number);
}

/** `14:00` → `2:00 PM`; anything that is not a clock time passes through. */
function clockTime(value: unknown): string {
  const raw = firstText(value);
  const match = raw.match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
  if (!match) return raw;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return raw;
  const suffix = hour >= 12 ? 'PM' : 'AM';
  return `${hour % 12 || 12}:${String(minute).padStart(2, '0')} ${suffix}`;
}

function feet(value: unknown): string {
  const n = finiteNumber(value);
  return n == null ? '' : `${formatNumber(n)} ft`;
}

function yesNo(value: unknown): string {
  if (value === true) return 'Yes';
  if (value === false) return 'No';
  return '';
}

/** Whether the backend says this pin has an availability provider (the 4d grid's gate). */
export function availabilitySupported(p: Props): boolean {
  return p.availability_supported === true || p.availabilitySupported === true;
}
