// Everything the campground drawer reasons about, as data.
//
// Functions return structured values — a detail row is
// `{ label, value }` where the value is text, a link, or chips — and the component
// decides how to render.
//
// Provider markup (fees, directions, description) is the one exception and does not
// live here: it goes through `lib/upstream-html.ts`, which is the only sanctioned
// `dangerouslySetInnerHTML` path.

/** POI properties after `flattenHydratedPoi`, which is deliberately open. */
type Props = Record<string, unknown>;

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

const AMENITY_LABELS = new Map<string, string>([
  ['camp_store', 'Camp store'],
  ['dump_station', 'Dump station'],
  ['electric_hookups', 'Electric hookups'],
  ['fires_allowed', 'Fires allowed'],
  ['pets_allowed', 'Pets allowed'],
  ['sewer_hookups', 'Sewer hookups'],
  ['showers', 'Showers'],
  ['toilets', 'Toilets'],
  ['trash', 'Trash'],
  ['water', 'Water'],
  ['water_hookups', 'Water hookups'],
  ['wifi', 'Wi-Fi'],
]);

/** Only some absences are worth stating: "No showers" is useful, "No camp store" is noise. */
const NEGATIVE_AMENITY_LABELS = new Map<string, string>([
  ['electric_hookups', 'No electric hookups'],
  ['sewer_hookups', 'No sewer hookups'],
  ['showers', 'No showers'],
  ['water', 'No water'],
  ['water_hookups', 'No water hookups'],
]);

/**
 * Amenity labels from either shape the field takes: a legacy array of strings, or the
 * canonical object of flags and values.
 *
 * `toilets` is skipped when `toilet_kind` is present, because the kind subsumes it
 * ("Vault toilets" rather than "Toilets" + "Vault").
 */
export function amenityList(p: Props): string[] {
  const value = p.amenities;
  if (Array.isArray(value)) return stringList(value);
  if (!value || typeof value !== 'object') return [];

  const record = value as Record<string, unknown>;
  const out: string[] = [];
  for (const [key, raw] of Object.entries(record)) {
    if (key === 'toilets' && record.toilet_kind) continue;
    const label = amenityLabel(key, raw);
    if (label) out.push(label);
  }
  return out;
}

export function activityList(p: Props): string[] {
  return stringList(p.activities);
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((v) => String(v).trim()).filter(Boolean);
}

function amenityLabel(key: string, value: unknown): string {
  if (value === null || value === undefined) return '';
  if (key === 'toilet_kind' && typeof value === 'string' && value.trim()) {
    return `${titleCase(value)} toilets`;
  }
  if (value === true) return AMENITY_LABELS.get(key) ?? titleCase(key);
  if (value === false) return NEGATIVE_AMENITY_LABELS.get(key) ?? '';
  if (typeof value === 'string' && value.trim()) {
    return `${AMENITY_LABELS.get(key) ?? titleCase(key)}: ${value.trim()}`;
  }
  return '';
}

// ---------------------------------------------------------------------------
// Cell coverage
// ---------------------------------------------------------------------------

const CARRIER_LABELS = new Map<string, string>([
  ['verizon', 'Verizon'],
  ['att', 'AT&T'],
  ['tmobile', 'T-Mobile'],
  ['sprint', 'Sprint'],
  ['uscell', 'US Cellular'],
]);

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
 * The field is `{ verizon: [avg, count], … }` on rec.gov pins and sometimes a bare
 * number per carrier; both shapes are read. Carriers with no usable average drop out
 * rather than rendering as a blank chip.
 */
export function carrierSignals(p: Props): CarrierSignal[] {
  const value = p.cell_coverage ?? p.cell_service;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];

  return Object.entries(value as Record<string, unknown>)
    .map(([carrier, raw]) => {
      const [avg, count] = Array.isArray(raw)
        ? [Number(raw[0]), Number(raw[1])]
        : [Number(raw), Number.NaN];
      return {
        carrier,
        label: CARRIER_LABELS.get(carrier) ?? carrier,
        avg,
        count: Number.isFinite(count) ? count : null,
        bucket: Math.max(0, Math.min(MAX_SIGNAL_BUCKET, Math.round(avg))),
      };
    })
    .filter((signal) => Number.isFinite(signal.avg))
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
  const value = p.rating_reviews;
  if (!Array.isArray(value)) return null;
  const average = Number(value[0]);
  const count = Number(value[1]);
  if (!Number.isFinite(average)) return null;
  return {
    average,
    count: Number.isFinite(count) ? count : 0,
    stars: stars(average),
  };
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
  const raw = p.last_verified;
  if (typeof raw !== 'string' || !raw.trim()) return null;
  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) return null;
  return {
    date: raw,
    stale: (now.getTime() - parsed.getTime()) / MS_PER_DAY > STALE_AFTER_DAYS,
  };
}

// ---------------------------------------------------------------------------
// Season verdict
// ---------------------------------------------------------------------------

export type VerdictTone = 'open' | 'closed' | 'fcfs';

export interface SeasonVerdict {
  tone: VerdictTone;
  text: string;
}

const MONTHS = new Map<string, number>([
  ['jan', 0],
  ['feb', 1],
  ['mar', 2],
  ['apr', 3],
  ['may', 4],
  ['jun', 5],
  ['jul', 6],
  ['aug', 7],
  ['sep', 8],
  ['sept', 8],
  ['oct', 9],
  ['nov', 10],
  ['dec', 11],
]);

/** "early May" / "mid June" / "late October" → a day of the month. */
const FUZZY_DAY = new Map<string, number>([
  ['early', 5],
  ['mid', 15],
  ['late', 25],
]);

/**
 * What to tell the user about the season, from a string a provider wrote by hand.
 *
 * `season` is loose — "mid-May to early October", "year-round (boat access)", null —
 * so this is a best-effort parse that says nothing rather than guessing. `reservable:
 * false` adds the first-come hint, and is the whole verdict when there is no season
 * string at all.
 */
export function seasonVerdict(
  season: unknown,
  reservable: unknown,
  now: Date = new Date(),
): SeasonVerdict | null {
  const fcfsHint = reservable === false ? ' · first-come' : '';

  if (typeof season !== 'string' || !season.trim()) {
    return reservable === false ? { tone: 'fcfs', text: 'First-come, first-served' } : null;
  }

  const range = parseSeasonRange(season, now.getFullYear());
  if (range) {
    if (now >= range.open && now <= range.close) {
      return { tone: 'open', text: `Open through ${monthDay(range.close)}${fcfsHint}` };
    }
    if (now < range.open) {
      return { tone: 'closed', text: `Closed until ${monthDay(range.open)}${fcfsHint}` };
    }
    // Past this year's close: the next opening is the same date next year.
    const nextOpen = new Date(range.open);
    nextOpen.setFullYear(nextOpen.getFullYear() + 1);
    return { tone: 'closed', text: `Closed until ${monthDay(nextOpen)}${fcfsHint}` };
  }

  if (/year[\s-]*round/i.test(season)) {
    return { tone: 'open', text: `Year-round${fcfsHint}` };
  }
  // Unparseable: show what the provider said rather than inventing a verdict.
  return { tone: 'fcfs', text: `${season}${fcfsHint}` };
}

function parseSeasonRange(season: string, year: number): { open: Date; close: Date } | null {
  const normalized = season.toLowerCase().replace(/[–—]/g, '-');
  const parts = normalized.split(/\s+to\s+|\s*->\s*|\s*through\s+/);
  if (parts.length < 2) return null;
  const open = parseDateBit(parts[0] ?? '', year);
  const close = parseDateBit(parts[1] ?? '', year);
  return open && close ? { open, close } : null;
}

function parseDateBit(text: string, year: number): Date | null {
  const fuzzy = text.match(/(early|mid|late)[\s-]+([a-z]+)/);
  if (fuzzy) {
    const month = monthFrom(fuzzy[2]);
    const day = FUZZY_DAY.get(fuzzy[1] ?? '');
    return month == null || day == null ? null : new Date(year, month, day);
  }
  const explicit = text.match(/([a-z]+)\.?\s+(\d{1,2})/);
  if (explicit) {
    const month = monthFrom(explicit[1]);
    return month == null ? null : new Date(year, month, Number.parseInt(explicit[2] ?? '1', 10));
  }
  return null;
}

/** Four letters first, so "sept" beats "sep". */
function monthFrom(word: string | undefined): number | null {
  if (!word) return null;
  return MONTHS.get(word.slice(0, 4)) ?? MONTHS.get(word.slice(0, 3)) ?? null;
}

function monthDay(date: Date): string {
  return date.toLocaleString('en-US', { month: 'short', day: 'numeric' });
}

// ---------------------------------------------------------------------------
// Calls to action
// ---------------------------------------------------------------------------

export interface Cta {
  url: string;
  label: string;
  variant: 'primary' | 'secondary';
}

/** No link to offer, and the pin is known to be first-come. */
export interface NoCta {
  disabledLabel: string;
}

export type CtaResult = Cta[] | NoCta;

export const isNoCta = (result: CtaResult): result is NoCta => !Array.isArray(result);

/**
 * The buttons a campground gets.
 *
 * The backend computes an ordered CTA list per pin (`provider_ref` + `info_url` →
 * vendor URLs and labels, including dated Aspira deeplinks), and the client renders it
 * verbatim — per-vendor URL precedence is a server concern. Everything below the first
 * branch is fallback for pins the backend could not resolve.
 */
export function campgroundCtas(p: Props): CtaResult {
  const provided = normalizeCtas(p.cta)
    .map((cta, index) => toCta(cta, index))
    .filter((cta): cta is Cta => cta !== null);
  if (provided.length > 0) return provided;

  const reserveUrl = safeUrl(firstText(p.reserve_url, p.reservation_url));
  if (reserveUrl) return [{ url: reserveUrl, label: reserveLabel(reserveUrl), variant: 'primary' }];

  const infoUrl = safeUrl(firstText(p.info_url, p.website));
  if (infoUrl) return [{ url: infoUrl, label: 'Visit website', variant: 'primary' }];

  // Marked first-come with nothing to link to: say so instead of offering a search
  // that implies a booking flow.
  if (p.reservable === false) return { disabledLabel: 'First-come, first-served' };

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
// Parent park
// ---------------------------------------------------------------------------

const PARENT_PARK_TITLE =
  /\b(park|preserve|forest|recreation area|recreation site|conservation area|wilderness|monument|seashore|lakeshore|reserve)\b/i;
const GENERIC_TITLE = /^(official\s+(page|site|website)|website|home|homepage|map|directions?)$/i;
const NON_PARENT_TITLE =
  /\b(reservations?|booking|fees?|passes?|permits?|map|directions?|calendar|alerts?|brochure|guide)\b/i;

/**
 * The containing park, inferred from official-link titles.
 *
 * A campground's own name stays the drawer's title; this only clarifies which unit
 * contains it ("Deception Pass State Park"). Titles that are generic ("Official site")
 * or clearly not a parent ("Reservations", "Fees") are rejected, and a link whose
 * title is just the campground again is skipped.
 */
export function parentParkName(p: Props): string {
  const own = normalizeTitle(firstText(p.name));
  if (!Array.isArray(p.links)) return '';

  for (const link of p.links) {
    if (!link || typeof link !== 'object') continue;
    const title = firstText(
      (link as Record<string, unknown>).title,
      (link as Record<string, unknown>).label,
      (link as Record<string, unknown>).name,
    );
    if (!title) continue;
    const normalized = normalizeTitle(title);
    if (!normalized || normalized === own) continue;
    if (GENERIC_TITLE.test(title) || NON_PARENT_TITLE.test(title)) continue;
    if (PARENT_PARK_TITLE.test(title)) return title;
  }
  return '';
}

function normalizeTitle(value: unknown): string {
  return String(value ?? '')
    .toLowerCase()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
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
 * The "More details" body, as groups of rows.
 *
 * Empty rows and empty groups drop out, so a sparse pin renders a short section
 * rather than a grid of blanks — and `hasDetails` below is how the drawer knows
 * whether to render the accordion at all.
 */
export function structuredDetails(p: Props): StructuredDetails {
  const stay: DetailRow[] = rows([
    ['Status', text(firstText(p.status_description, p.status ? titleCase(p.status) : ''))],
    ['Price', text(priceRange(p.price))],
    ['Check-in', text(scheduleTime(p.schedule, 'check_in_time'))],
    ['Check-out', text(scheduleTime(p.schedule, 'check_out_time'))],
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

  const metadata = p.metadata as Record<string, unknown> | undefined;
  const source: DetailRow[] = rows([
    ['Data source', text(firstText(sourcesLabel(p.sources), p.source))],
    ['Source ID', text(firstText(p.source_id))],
    ['Availability provider', text(firstText(p.availability_provider, p.provider_source))],
    [
      'Booking site',
      text(firstText(p.booking_site, urlHost(firstText(p.reserve_url, p.reservation_url)))),
    ],
    ['Last updated', text(firstText(metadata?.last_updated, p.last_verified))],
    ['Connections', connections(p.connections)],
  ]);

  return {
    groups: [
      { title: 'Stay details', rows: stay },
      { title: 'Contact', rows: contact },
      { title: 'Source metadata', rows: source },
    ].filter((group) => group.rows.length > 0),
    links: campgroundLinks(p.links),
    alerts: campgroundAlerts(p.alerts),
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

function campgroundAlerts(value: unknown): CampgroundAlert[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((alert) => {
      if (typeof alert === 'string') {
        const body = alert.trim();
        return body ? { title: '', body } : null;
      }
      if (!alert || typeof alert !== 'object') return null;
      const record = alert as Record<string, unknown>;
      const title = firstText(record.title, record.name, record.headline, record.type);
      const body = firstText(record.description, record.message, record.body, record.text);
      return title || body ? { title, body } : null;
    })
    .filter((alert): alert is CampgroundAlert => alert !== null);
}

function sourcesLabel(sources: unknown): string {
  if (!Array.isArray(sources)) return '';
  const cleaned = sources
    .map((v) => (typeof v === 'string' || typeof v === 'number' ? String(v).trim() : ''))
    .filter(Boolean);
  // Deduped: a POI merged from two feeds of the same vendor lists it once.
  return [...new Set(cleaned)].join(', ');
}

/** `$25`, `$25-$40`, or `CAD 30` for currencies with no familiar symbol. */
function priceRange(price: unknown): string {
  if (!price || typeof price !== 'object') return '';
  const record = price as Props;
  const min = finiteNumber(record.minimum ?? record.min);
  const max = finiteNumber(record.maximum ?? record.max);
  if (min == null && max == null) return '';

  const currency = firstText(record.currency_code, record.currency) || 'USD';
  const symbol = ['USD', 'CAD'].includes(currency.toUpperCase())
    ? '$'
    : `${currency.toUpperCase()} `;
  const format = (n: number) => `${symbol}${formatNumber(n)}`;

  if (min != null && max != null && min !== max) return `${format(min)}-${format(max)}`;
  return format((min ?? max) as number);
}

function scheduleTime(schedule: unknown, key: string): string {
  if (!schedule || typeof schedule !== 'object') return '';
  return clockTime((schedule as Props)[key]);
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
