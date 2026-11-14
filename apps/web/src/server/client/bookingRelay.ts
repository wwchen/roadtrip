import { BOOKING_RELAY_CLIENT_TIMEOUT_MS, type BookingConfig } from '../config';
import type { BookingRelayResponseDto } from '../contract';

export const BOOKING_RELAY_PREFIX = '/api/internal/booking';
export const RELAY_TIMEOUT_CODE = 'timeout';
export const RELAY_UNREACHABLE_CODE = 'unreachable';
export const RELAY_UNAUTHORIZED_CODE = 'relay_unauthorized';
export const RELAY_REDIRECTED_CODE = 'relay_redirected';
export const RELAY_NOT_JSON_CODE = 'relay_not_json';
export const RELAY_REJECTED_CODE = 'relay_rejected';
const STATUS_UNAUTHORIZED = 401;
const FIRST_REDIRECT_STATUS = 300;
const FIRST_CLIENT_ERROR_STATUS = 400;
const TIMEOUT_ERROR = 'TimeoutError';
const ACCESS_CLIENT_ID_HEADER = 'cf-access-client-id';
const ACCESS_CLIENT_SECRET_HEADER = 'cf-access-client-secret';
const BEARER_PREFIX = 'Bearer ';

export type BookingRelayPath = 'health' | 'login' | 'login/mfa' | 'refresh' | 'verify' | 'atc' | 'signout';

export type RelayFailureCode =
  | typeof RELAY_TIMEOUT_CODE
  | typeof RELAY_UNREACHABLE_CODE
  | typeof RELAY_UNAUTHORIZED_CODE
  | typeof RELAY_REDIRECTED_CODE
  | typeof RELAY_NOT_JSON_CODE
  | typeof RELAY_REJECTED_CODE;

export type RelayResult =
  | { ok: true; status: number; answer: BookingRelayResponseDto }
  | { ok: false; code: RelayFailureCode };

function isEnvelope(body: unknown): body is BookingRelayResponseDto {
  return typeof body === 'object' && body !== null && typeof (body as { ok?: unknown }).ok === 'boolean';
}

export async function relay(
  config: BookingConfig,
  path: BookingRelayPath,
  body: object,
  timeoutMs: number = BOOKING_RELAY_CLIENT_TIMEOUT_MS,
): Promise<RelayResult> {
  let response: Response;
  try {
    response = await fetch(new URL(`${BOOKING_RELAY_PREFIX}/${path}`, config.relayUrl), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `${BEARER_PREFIX}${config.relayToken}`,
        [ACCESS_CLIENT_ID_HEADER]: config.access.clientId,
        [ACCESS_CLIENT_SECRET_HEADER]: config.access.clientSecret,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
      cache: 'no-store',
      redirect: 'manual',
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === TIMEOUT_ERROR;
    return { ok: false, code: timedOut ? RELAY_TIMEOUT_CODE : RELAY_UNREACHABLE_CODE };
  }
  if (response.status >= FIRST_REDIRECT_STATUS && response.status < FIRST_CLIENT_ERROR_STATUS) {
    return { ok: false, code: RELAY_REDIRECTED_CODE };
  }
  if (response.status === STATUS_UNAUTHORIZED) return { ok: false, code: RELAY_UNAUTHORIZED_CODE };
  const parsed: unknown = await response.json().catch(() => undefined);
  if (parsed === undefined) return { ok: false, code: RELAY_NOT_JSON_CODE };
  if (isEnvelope(parsed)) return { ok: true, status: response.status, answer: parsed };
  return { ok: false, code: response.ok ? RELAY_NOT_JSON_CODE : RELAY_REJECTED_CODE };
}
