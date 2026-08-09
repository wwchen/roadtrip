// Deep links into a provider's booking flow.
//
// Port of web/availability/booking-links.js. The backend hands us a per-campsite
// URL *template* rather than a URL, because the link depends on the stay window the
// user picked — so a booking link cannot be built until a date is selected, and
// this module is the only thing that knows that.
import type { Campsite } from '@/api/campsite-api';

/** Substitutions a template may ask for. A template with none is already a URL. */
const TEMPLATE_PLACEHOLDERS = ['{start_date}', '{end_date}', '{nights}'] as const;

const MS_PER_DAY = 86_400_000;

/**
 * Host → the name a user recognises.
 *
 * A table because the mapping is data: adding a provider should be a row, not
 * another `if`. A `Map` rather than an object literal for the reason the other
 * registries in this codebase are — the key comes from a URL.
 */
const AGENCY_BY_HOST = new Map<string, string>([
  ['recreation.gov', 'Recreation.gov'],
  ['www.recreation.gov', 'Recreation.gov'],
  ['reservation.pc.gc.ca', 'Parks Canada'],
  ['camping.bcparks.ca', 'BC Parks'],
  ['discovercamping.ca', 'BC Parks'],
  ['washington.goingtocamp.com', 'Washington State Parks'],
]);

/** Vendor slug → display name, for rows whose template we cannot parse. */
const AGENCY_BY_VENDOR = new Map<string, string>([
  ['recgov', 'Recreation.gov'],
  ['campflare', 'Campflare'],
  ['aspira', 'Aspira'],
]);

/** `campsiteId → template`, as `/api/pois/{id}/campsites` returns it. */
export type ReservationUrlTemplates =
  | Record<string | number, string>
  | Map<string | number, string>
  | null
  | undefined;

export interface StayWindow {
  startDate?: string | null;
  /** Exclusive, matching the availability window. */
  endDate?: string | null;
  reservationUrlTemplates?: ReservationUrlTemplates;
}

/**
 * A bookable URL for a campsite over a stay window, or `''`.
 *
 * Empty is a meaningful answer and the callers depend on it: no template means
 * this provider has no deep link, and a template *with* placeholders but no dates
 * means the link cannot be built yet. Returning a half-substituted URL would send
 * someone to a booking page for the wrong nights, which is worse than no link.
 */
export function reservationUrlFromTemplate(
  row: Partial<Campsite> | null | undefined,
  { startDate, endDate, reservationUrlTemplates }: StayWindow = {},
): string {
  const template = reservationUrlTemplate(row, reservationUrlTemplates);
  if (!template) return '';
  if (!hasTemplatePlaceholders(template)) return template;
  if (!startDate || !endDate) return '';

  const nights = nightsBetween(startDate, endDate);
  if (!Number.isFinite(nights) || nights <= 0) return '';

  return template
    .replaceAll('{start_date}', startDate)
    .replaceAll('{end_date}', endDate)
    .replaceAll('{nights}', String(nights));
}

/**
 * Whether a row could ever be booked through us.
 *
 * Separate from `reservationUrlFromTemplate` returning a URL: the matrix decides
 * whether a cell is a button *before* a date is chosen, so it needs "there is a
 * template" rather than "there is a link right now".
 */
export function hasReservationUrlTemplate(
  row: Partial<Campsite> | null | undefined,
  reservationUrlTemplates: ReservationUrlTemplates,
): boolean {
  return !!reservationUrlTemplate(row, reservationUrlTemplates);
}

/** "Book on Recreation.gov", or plain "Book" when the provider is unrecognised. */
export function bookingLabel(
  row: Partial<Campsite> | null | undefined,
  reservationUrlTemplates: ReservationUrlTemplates,
): string {
  const agency = agencyLabel(row, reservationUrlTemplates);
  return agency ? `Book on ${agency}` : 'Book';
}

/**
 * Who takes the booking, by preference: the template's host, then the row's
 * vendor slug, then a guess humanised from whichever we have.
 */
export function agencyLabel(
  row: Partial<Campsite> | null | undefined,
  reservationUrlTemplates: ReservationUrlTemplates,
): string {
  const template = reservationUrlTemplate(row, reservationUrlTemplates);
  const host = hostFromUrl(template);
  const known = AGENCY_BY_HOST.get(host);
  if (known) return known;

  const vendor = String(row?.booking_provider || row?.data_provider || '').toLowerCase();
  return AGENCY_BY_VENDOR.get(vendor) || labelFromHost(host) || humanizeAgency(vendor);
}

function reservationUrlTemplate(
  row: Partial<Campsite> | null | undefined,
  reservationUrlTemplates: ReservationUrlTemplates,
): string {
  const raw = templateForRow(row, reservationUrlTemplates);
  return typeof raw === 'string' ? raw.trim() : '';
}

function templateForRow(
  row: Partial<Campsite> | null | undefined,
  reservationUrlTemplates: ReservationUrlTemplates,
): unknown {
  if (!row || row.id == null || !reservationUrlTemplates) return '';
  if (reservationUrlTemplates instanceof Map) {
    return reservationUrlTemplates.get(String(row.id)) || reservationUrlTemplates.get(row.id) || '';
  }
  if (typeof reservationUrlTemplates === 'object') {
    // Own-property only: the ids come off a JSON body, and a plain-object lookup
    // would happily resolve `constructor` (see `lib/settings-errors.ts`).
    return Object.prototype.hasOwnProperty.call(reservationUrlTemplates, String(row.id))
      ? reservationUrlTemplates[String(row.id)]
      : '';
  }
  return '';
}

function hasTemplatePlaceholders(template: string): boolean {
  return TEMPLATE_PLACEHOLDERS.some((placeholder) => template.includes(placeholder));
}

/** Whole days between two ISO dates, in UTC so a DST boundary cannot round to 0. */
function nightsBetween(startDate: string, endDate: string): number {
  const start = Date.parse(`${startDate}T00:00:00Z`);
  const end = Date.parse(`${endDate}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return NaN;
  return Math.round((end - start) / MS_PER_DAY);
}

function hostFromUrl(url: string): string {
  if (!url) return '';
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return '';
  }
}

function labelFromHost(host: string): string {
  const base = String(host || '')
    .replace(/^www\./, '')
    .split('.')[0];
  return base ? humanizeAgency(base) : '';
}

function humanizeAgency(key: string): string {
  return String(key)
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (char) => char.toUpperCase());
}
