import { BOOKING_RELAY_CLIENT_TIMEOUT_MS, type BookingConfig } from '../config';
import {
  atcRequest,
  decodeAction,
  decodeHealth,
  decodeHold,
  decodeLogin,
  decodeSignOut,
  loginRequest,
  mfaRequest,
  profileRequest,
  shapeFault,
  type RelayExchange,
} from '../mapper/relay/bookingRelayWire';
import type { ActionResult, GatewayFault, HoldResult, LoginResult, SessionHealth, SignOutResult } from '../service/booking/model';
import type { BookingGateway } from '../service/booking/ports';
import { INTERNAL_API_VERSION, INTERNAL_API_VERSION_HEADER, INTERNAL_VERSION_UNSUPPORTED } from './internalApi';

export const BOOKING_RELAY_PREFIX = '/api/internal/booking';
const STATUS_BAD_REQUEST = 400;
const STATUS_UNAUTHORIZED = 401;
const FIRST_REDIRECT_STATUS = 300;
const FIRST_CLIENT_ERROR_STATUS = 400;
const TIMEOUT_ERROR = 'TimeoutError';
const ACCESS_CLIENT_ID_HEADER = 'cf-access-client-id';
const ACCESS_CLIENT_SECRET_HEADER = 'cf-access-client-secret';
const BEARER_PREFIX = 'Bearer ';
const GATEWAY_LOG_PREFIX = 'booking gateway:';
const LOGGED_DECODE_FAULTS: readonly GatewayFault[] = ['malformed', 'rejected'];
const NOT_CONFIGURED: { kind: 'unavailable'; fault: GatewayFault } = { kind: 'unavailable', fault: 'not_configured' };

export type BookingRelayPath = 'health' | 'login' | 'login/mfa' | 'refresh' | 'verify' | 'atc' | 'signout';

function refusesOurVersion(body: unknown): boolean {
  return typeof body === 'object' && body !== null && (body as { error?: unknown }).error === INTERNAL_VERSION_UNSUPPORTED;
}

function fault(kind: GatewayFault): RelayExchange {
  console.warn(GATEWAY_LOG_PREFIX, kind);
  return { kind: 'fault', fault: kind };
}

export async function postRelay(
  config: BookingConfig,
  path: BookingRelayPath,
  body: object,
  timeoutMs: number = BOOKING_RELAY_CLIENT_TIMEOUT_MS,
): Promise<RelayExchange> {
  let response: Response;
  try {
    response = await fetch(new URL(`${BOOKING_RELAY_PREFIX}/${path}`, config.relayUrl), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `${BEARER_PREFIX}${config.relayToken}`,
        [ACCESS_CLIENT_ID_HEADER]: config.access.clientId,
        [ACCESS_CLIENT_SECRET_HEADER]: config.access.clientSecret,
        [INTERNAL_API_VERSION_HEADER]: INTERNAL_API_VERSION,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
      cache: 'no-store',
      redirect: 'manual',
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === TIMEOUT_ERROR;
    return fault(timedOut ? 'unanswered' : 'unreachable');
  }
  if (response.status >= FIRST_REDIRECT_STATUS && response.status < FIRST_CLIENT_ERROR_STATUS) return fault('redirected');
  if (response.status === STATUS_UNAUTHORIZED) return fault('unauthorized');
  const parsed: unknown = await response.json().catch(() => undefined);
  if (parsed === undefined) return fault('malformed');
  if (response.status === STATUS_BAD_REQUEST && refusesOurVersion(parsed)) return fault('version_mismatch');
  return { kind: 'answered', status: response.status, body: parsed };
}

function resultFault(exchange: RelayExchange, result: object): GatewayFault | null {
  if (exchange.kind !== 'answered' || !('fault' in result)) return null;
  return LOGGED_DECODE_FAULTS.find((candidate) => candidate === result.fault) ?? null;
}

export function bookingRelayGateway(config: BookingConfig): BookingGateway {
  async function call<T extends { kind: string }>(path: BookingRelayPath, body: object, decode: (exchange: RelayExchange) => T): Promise<T> {
    const exchange = await postRelay(config, path, body);
    const result = decode(exchange);
    const decodeFault = shapeFault(exchange) ?? resultFault(exchange, result);
    if (decodeFault !== null && LOGGED_DECODE_FAULTS.includes(decodeFault)) console.warn(GATEWAY_LOG_PREFIX, decodeFault);
    return result;
  }
  return {
    hold: (userId, vendorSiteId, window) => call('atc', atcRequest(userId, vendorSiteId, window), decodeHold),
    signIn: (userId, username, password) => call('login', loginRequest(userId, username, password), decodeLogin),
    submitMfa: (userId, challengeId, code) => call('login/mfa', mfaRequest(userId, challengeId, code), decodeLogin),
    refresh: (userId) => call('refresh', profileRequest(userId), decodeAction),
    verify: (userId) => call('verify', profileRequest(userId), decodeAction),
    health: (userId) => call('health', profileRequest(userId), decodeHealth),
    signOut: (userId) => call('signout', profileRequest(userId), decodeSignOut),
  };
}

export const unconfiguredBookingGateway: BookingGateway = {
  hold: async (): Promise<HoldResult> => NOT_CONFIGURED,
  signIn: async (): Promise<LoginResult> => NOT_CONFIGURED,
  submitMfa: async (): Promise<LoginResult> => NOT_CONFIGURED,
  refresh: async (): Promise<ActionResult> => NOT_CONFIGURED,
  verify: async (): Promise<ActionResult> => NOT_CONFIGURED,
  health: async (): Promise<SessionHealth> => NOT_CONFIGURED,
  signOut: async (): Promise<SignOutResult> => NOT_CONFIGURED,
};
