// Turning an availability failure into something worth reading.
//
import { rawErrorText } from '@/lib/error-report';

/** Keyed by the `error` code on the backend's `AvailabilityErrorDto`. */
const AVAILABILITY_ERROR_LABELS = new Map<string, string>([
  ['rate_limited', 'Booking site rate-limited us — try again shortly'],
  ['upstream_blocked', 'Booking site blocked the request'],
  ['upstream_5xx', 'Booking site returned an error'],
  ['upstream_unreachable', 'Could not reach the booking site'],
  ['unsupported', 'Provider not supported'],
  ['provider_misconfigured', 'Provider misconfigured — we are on it'],
  ['ip_throttled', 'Too many requests from this network — slow down'],
  ['availability_unavailable', 'Availability is unavailable right now — try again shortly'],
  ['beyond_booking_horizon', 'That date is past what this provider lets you book'],
  ['start_before_earliest', 'That date is before this campground takes bookings'],
]);

const FACT_SEPARATOR = ' · ';

export interface AvailabilityErrorBody {
  error?: unknown;
  detail?: unknown;
  upstream_status?: unknown;
  provider_display?: unknown;
  earliest_date?: unknown;
}

export type AvailabilityErrorKind = 'throttled' | 'server_error' | 'unreachable' | 'other';

const THROTTLED_CODES = new Set(['rate_limited']);

export function classifyAvailabilityErrorCode(code: string | null): AvailabilityErrorKind {
  if (code && THROTTLED_CODES.has(code)) return 'throttled';
  if (code === 'upstream_5xx') return 'server_error';
  if (code === 'upstream_unreachable') return 'unreachable';
  return 'other';
}

function stringOf(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

export function rawAvailabilityError(body: AvailabilityErrorBody | null | undefined, httpStatus: number): string {
  const raw = rawErrorText({ code: stringOf(body?.error), detail: stringOf(body?.detail), status: httpStatus });
  return typeof body?.upstream_status === 'number'
    ? [raw, `upstream HTTP ${body.upstream_status}`].join(FACT_SEPARATOR)
    : raw;
}

export function formatAvailabilityError(
  body: AvailabilityErrorBody | null | undefined,
  httpStatus: number,
): string {
  const code = stringOf(body?.error);
  const label = code ? AVAILABILITY_ERROR_LABELS.get(code) : undefined;
  const raw = rawAvailabilityError(body, httpStatus);
  return label ? `${label} (${raw})` : raw;
}
