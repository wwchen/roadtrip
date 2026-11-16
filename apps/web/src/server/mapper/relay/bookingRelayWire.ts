import type {
  BookingRelayActionDto,
  BookingRelayActionOutcome,
  BookingRelayAtcRequest,
  BookingRelayFailureCategory,
  BookingRelayFailureDto,
  BookingRelayHealthDto,
  BookingRelayHoldDto,
  BookingRelayHoldOutcome,
  BookingRelayLoginDto,
  BookingRelayLoginOutcome,
  BookingRelayLoginRequest,
  BookingRelayMfaRequest,
  BookingRelayProfileRequest,
  BookingRelaySession,
  BookingRelaySignOutDto,
  BookingRelaySignOutOutcome,
} from '@/server/contract';
import type {
  ActionResult,
  GatewayFault,
  HoldResult,
  LoginResult,
  Refusal,
  RefusalCategory,
  SessionHealth,
  SignOutResult,
  StayWindow,
} from '@/server/service/booking/model';

export type RelayExchange = { kind: 'answered'; status: number; body: unknown } | { kind: 'fault'; fault: GatewayFault };

const PROFILE_ID_PREFIX = 'n:';
const FIRST_SUCCESS_STATUS = 200;
const FIRST_REDIRECT_STATUS = 300;
const STATUS_GATEWAY_TIMEOUT = 504;
const HTTPS_PROTOCOL = 'https:';
const OUTCOME_FIELD = 'outcome' satisfies keyof BookingRelayHoldDto;
const SESSION_FIELD = 'session' satisfies keyof BookingRelayHealthDto;
const REFUSAL_CATEGORIES: readonly (RefusalCategory & BookingRelayFailureCategory)[] = ['caller_action', 'retry_later', 'upstream'];

const HOLD = { held: 'held', blocked: 'blocked', timedOut: 'timed_out' } as const satisfies Record<string, BookingRelayHoldOutcome>;
const LOGIN = { signedIn: 'signed_in', mfaRequired: 'mfa_required', failed: 'failed', timedOut: 'timed_out' } as const satisfies Record<string, BookingRelayLoginOutcome>;
const ACTION = { ok: 'ok', failed: 'failed', timedOut: 'timed_out' } as const satisfies Record<string, BookingRelayActionOutcome>;
const SIGN_OUT = { wiped: 'wiped', failed: 'failed', timedOut: 'timed_out' } as const satisfies Record<string, BookingRelaySignOutOutcome>;
const HEALTH = {
  active: 'active',
  neverLoggedIn: 'never_logged_in',
  inactive: 'inactive',
  checkFailed: 'check_failed',
  unavailable: 'unavailable',
} as const satisfies Record<string, BookingRelaySession>;

type Unavailable = { kind: 'unavailable'; fault: GatewayFault };
type Raw<T> = { [K in keyof T]?: unknown };
type Tagged = { [K in typeof OUTCOME_FIELD | typeof SESSION_FIELD]?: unknown };

const MALFORMED: Unavailable = { kind: 'unavailable', fault: 'malformed' };

function profileId(userId: bigint): string {
  return `${PROFILE_ID_PREFIX}${userId}`;
}

export function profileRequest(userId: bigint): BookingRelayProfileRequest {
  return { profile_id: profileId(userId) };
}

export function loginRequest(userId: bigint, username: string, password: string): BookingRelayLoginRequest {
  return { profile_id: profileId(userId), username, password };
}

export function mfaRequest(userId: bigint, challengeId: string, code: string): BookingRelayMfaRequest {
  return { profile_id: profileId(userId), challenge_id: challengeId, mfa_code: code };
}

export function atcRequest(userId: bigint, vendorSiteId: string, window: StayWindow): BookingRelayAtcRequest {
  return { profile_id: profileId(userId), vendor_site_id: vendorSiteId, start_date: window.startDate, end_date: window.endDate };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isTagged(body: unknown): body is Tagged {
  return isObject(body) && (typeof body[OUTCOME_FIELD] === 'string' || typeof body[SESSION_FIELD] === 'string');
}

function isAnswerStatus(status: number): boolean {
  return (status >= FIRST_SUCCESS_STATUS && status < FIRST_REDIRECT_STATUS) || status === STATUS_GATEWAY_TIMEOUT;
}

function answerFault(status: number, body: unknown): GatewayFault | null {
  if (!isAnswerStatus(status)) return 'rejected';
  return isTagged(body) ? null : 'malformed';
}

export function shapeFault(exchange: RelayExchange): GatewayFault | null {
  return exchange.kind === 'fault' ? null : answerFault(exchange.status, exchange.body);
}

function open<T>(exchange: RelayExchange): Raw<T> | GatewayFault {
  if (exchange.kind === 'fault') return exchange.fault;
  return answerFault(exchange.status, exchange.body) ?? (exchange.body as Raw<T>);
}

function unavailable(fault: GatewayFault): Unavailable {
  return { kind: 'unavailable', fault };
}

function stringFields<T, K extends keyof T & string>(body: Raw<T>, ...keys: K[]): { [P in K]?: string } {
  const out: { [P in K]?: string } = {};
  for (const key of keys) {
    const value = body[key];
    if (typeof value === 'string') out[key] = value;
  }
  return out;
}

function categoryOf(value: unknown): RefusalCategory | null {
  return REFUSAL_CATEGORIES.find((category) => category === value) ?? null;
}

function refusalOf(body: Raw<{ failure: BookingRelayFailureDto }>): Refusal | null {
  const failure: unknown = body.failure;
  if (!isObject(failure)) return null;
  const category = categoryOf(failure.category);
  if (category === null || typeof failure.code !== 'string') return null;
  return { category, code: failure.code, ...stringFields<BookingRelayFailureDto, 'detail'>(failure, 'detail') };
}

function refused<R>(body: Raw<{ failure: BookingRelayFailureDto }>, build: (refusal: Refusal) => R): R | Unavailable {
  const refusal = refusalOf(body);
  return refusal === null ? MALFORMED : build(refusal);
}

function httpsUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    return new URL(value).protocol === HTTPS_PROTOCOL ? value : null;
  } catch {
    return null;
  }
}

export function decodeHold(exchange: RelayExchange): HoldResult {
  const body = open<BookingRelayHoldDto>(exchange);
  if (typeof body === 'string') return unavailable(body);
  const cartUrl = httpsUrl(body.cart_url);
  switch (body.outcome) {
    case HOLD.held:
      return cartUrl === null ? MALFORMED : { kind: 'held', cartUrl };
    case HOLD.blocked:
      return refused(body, (refusal) => ({ kind: 'blocked', refusal }));
    case HOLD.timedOut:
      return refused(body, (refusal) => ({ kind: 'timed_out', refusal, ...(cartUrl === null ? {} : { cartUrl }) }));
    default:
      return MALFORMED;
  }
}

export function decodeLogin(exchange: RelayExchange): LoginResult {
  const body = open<BookingRelayLoginDto>(exchange);
  if (typeof body === 'string') return unavailable(body);
  switch (body.outcome) {
    case LOGIN.signedIn:
      return { kind: 'signed_in' };
    case LOGIN.mfaRequired:
      return typeof body.challenge_id === 'string'
        ? { kind: 'mfa_required', challengeId: body.challenge_id, ...(typeof body.expires_at === 'string' ? { expiresAt: body.expires_at } : {}) }
        : MALFORMED;
    case LOGIN.failed:
      return refused(body, (refusal) => ({ kind: 'failed', refusal }));
    case LOGIN.timedOut:
      return refused(body, (refusal) => ({ kind: 'timed_out', refusal }));
    default:
      return MALFORMED;
  }
}

export function decodeAction(exchange: RelayExchange): ActionResult {
  const body = open<BookingRelayActionDto>(exchange);
  if (typeof body === 'string') return unavailable(body);
  switch (body.outcome) {
    case ACTION.ok:
      return { kind: 'ok' };
    case ACTION.failed:
      return refused(body, (refusal) => ({ kind: 'failed', refusal }));
    case ACTION.timedOut:
      return refused(body, (refusal) => ({ kind: 'timed_out', refusal }));
    default:
      return MALFORMED;
  }
}

export function decodeHealth(exchange: RelayExchange): SessionHealth {
  const body = open<BookingRelayHealthDto>(exchange);
  if (typeof body === 'string') return { kind: 'unavailable' };
  switch (body.session) {
    case HEALTH.active:
      return { kind: 'active' };
    case HEALTH.neverLoggedIn:
      return { kind: 'never_logged_in' };
    case HEALTH.inactive:
      return { kind: 'inactive', ...stringFields<BookingRelayHealthDto, 'code'>(body, 'code') };
    case HEALTH.checkFailed:
      return { kind: 'check_failed', ...stringFields<BookingRelayHealthDto, 'code' | 'detail'>(body, 'code', 'detail') };
    case HEALTH.unavailable:
    default:
      return { kind: 'unavailable', ...stringFields<BookingRelayHealthDto, 'code' | 'detail'>(body, 'code', 'detail') };
  }
}

export function decodeSignOut(exchange: RelayExchange): SignOutResult {
  const body = open<BookingRelaySignOutDto>(exchange);
  if (typeof body === 'string') return unavailable(body);
  const { signed_out: signedOut, profile_destroyed: profileDestroyed } = body;
  if (typeof signedOut !== 'boolean' || typeof profileDestroyed !== 'boolean') return MALFORMED;
  switch (body.outcome) {
    case SIGN_OUT.wiped:
      return { kind: 'wiped', signedOut };
    case SIGN_OUT.failed:
      return refused(body, (refusal) => ({ kind: 'failed', signedOut, refusal }));
    case SIGN_OUT.timedOut:
      return refused(body, (refusal) => ({ kind: 'timed_out', refusal }));
    default:
      return MALFORMED;
  }
}
