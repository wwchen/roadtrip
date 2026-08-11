// Turning an availability failure into something worth reading.
//

/** Keyed by the `error` code on the backend's `AvailabilityErrorDto`. */
const AVAILABILITY_ERROR_LABELS = new Map<string, string>([
  ['rate_limited', 'Booking site rate-limited us — try again shortly'],
  ['upstream_blocked', 'Booking site blocked the request'],
  ['upstream_5xx', 'Booking site returned an error'],
  ['upstream_unreachable', 'Could not reach the booking site'],
  ['unsupported', 'Provider not supported'],
  ['provider_misconfigured', 'Provider misconfigured — we are on it'],
  ['ip_throttled', 'Too many requests — slow down'],
]);

/** What the week grid shows when the request could not be fulfilled. */
export const GENERIC_AVAILABILITY_ERROR = "Couldn't load availability";

interface AvailabilityErrorBody {
  error?: unknown;
  upstream_status?: unknown;
}

/**
 * A sentence for an error body and HTTP status.
 *
 * An unrecognised code is shown verbatim rather than replaced with generic copy:
 * a code we have not written prose for is still more useful to whoever reads the
 * screenshot than "something went wrong". The upstream status is appended when the
 * backend passed one through, because "blocked (upstream HTTP 403)" and "blocked
 * (upstream HTTP 503)" are different conversations.
 */
export function formatAvailabilityError(
  body: AvailabilityErrorBody | null | undefined,
  httpStatus: number,
): string {
  const code = typeof body?.error === 'string' ? body.error : null;
  const base = code ? AVAILABILITY_ERROR_LABELS.get(code) ?? code : `HTTP ${httpStatus}`;
  return typeof body?.upstream_status === 'number'
    ? `${base} (upstream HTTP ${body.upstream_status})`
    : base;
}
