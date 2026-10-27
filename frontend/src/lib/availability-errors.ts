const MESSAGES = new Map<string, string>([
  ['rate_limited', 'Booking site rate-limited us — try again shortly'],
  ['upstream_blocked', 'Booking site blocked the request'],
  ['upstream_5xx', 'Booking site returned an error'],
  ['upstream_unreachable', 'Could not reach the booking site'],
  ['unsupported', 'Provider not supported'],
  ['provider_misconfigured', 'Provider misconfigured — we are on it'],
  ['ip_throttled', 'Too many requests — slow down'],
  ['beyond_booking_horizon', 'That date is past what this provider lets you book'],
]);

export const GENERIC_AVAILABILITY_ERROR = "Couldn't load availability";

export type AvailabilityErrorKind = 'throttled' | 'server_error' | 'unreachable' | 'other';

const THROTTLED_CODES = new Set(['rate_limited', 'ip_throttled', 'upstream_blocked']);

export interface AvailabilityErrorBody {
  error?: unknown;
  upstream_status?: unknown;
}

export class AvailabilityRequestError extends Error {
  constructor(
    message: string,
    readonly httpStatus: number,
    readonly code: string | null = null,
  ) {
    super(message);
    this.name = 'AvailabilityRequestError';
  }
}

export function classifyAvailabilityErrorCode(code: string | null): AvailabilityErrorKind {
  if (code && THROTTLED_CODES.has(code)) return 'throttled';
  if (code === 'upstream_5xx') return 'server_error';
  if (code === 'upstream_unreachable') return 'unreachable';
  return 'other';
}

export function retryingHelps(kind: AvailabilityErrorKind): boolean {
  return kind !== 'throttled';
}

export function formatAvailabilityError(
  body: AvailabilityErrorBody | null | undefined,
  httpStatus: number,
): string {
  const code = typeof body?.error === 'string' ? body.error : null;
  const base = code ? MESSAGES.get(code) ?? code : `HTTP ${httpStatus}`;
  return typeof body?.upstream_status === 'number'
    ? `${base} (upstream HTTP ${body.upstream_status})`
    : base;
}
