// Deep links into a provider's booking flow.
//
// The backend hands us a per-campsite URL template because it depends on the stay window the
// user picked — so a booking link cannot be built until a date is selected, and
// this module is the only thing that knows that.
import { bookingCopy } from '@/lib/strings';
import type { Campsite } from '@/api/campsite-api';

/** Substitutions a template may ask for. A template with none is already a URL. */
const TEMPLATE_PLACEHOLDERS = ['{start_date}', '{end_date}', '{nights}'] as const;

const MS_PER_DAY = 86_400_000;

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

/**
 * "Book on <whoever takes the booking>", or plain "Book".
 *
 * The name is the backend's `booking_system` for this row — the same resolver
 * the drawer uses. The frontend no longer guesses one from a URL host or a
 * vendor slug, which is how `aspira` used to read "Aspira" here and
 * "Aspira NextGen" one screen over.
 *
 * The named branch is `bookingCopy.bookOn`, shared with the cell popover's
 * escape hatch; only the unnamed fallback differs, because a 66px cell has no
 * room for the popover's "the booking site".
 */
export function bookingLabel(row: Partial<Campsite> | null | undefined): string {
  const site = String(row?.booking_system || '').trim();
  return site ? bookingCopy.bookOn(site) : bookingCopy.book;
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
