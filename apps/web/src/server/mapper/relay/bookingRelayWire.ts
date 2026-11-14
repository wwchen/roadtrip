import type {
  BookingRelayAtcRequest,
  BookingRelayLoginRequest,
  BookingRelayMfaRequest,
  BookingRelayProfileRequest,
  BookingRelayResponseDto,
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
const MFA_REQUIRED_CODE = 'mfa_required';
const FALLBACK_CATEGORY: RefusalCategory = 'upstream';
const REFUSAL_CATEGORIES: readonly string[] = ['caller_action', 'retry_later', 'upstream'] satisfies RefusalCategory[];
const HEALTH_ACTIVE = 'active';
const HEALTH_NEVER_LOGGED_IN = 'never_logged_in';
const HEALTH_INACTIVE = 'inactive';
const HEALTH_CHECK_FAILED = 'check_failed';
const HEALTH_UNAVAILABLE = 'unavailable';

type Unavailable = { kind: 'unavailable'; fault: GatewayFault };
type Opened = { status: number; envelope: BookingRelayResponseDto };

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

function isEnvelope(body: unknown): body is BookingRelayResponseDto {
  return typeof body === 'object' && body !== null && typeof (body as { ok?: unknown }).ok === 'boolean';
}

function isSuccessStatus(status: number): boolean {
  return status >= FIRST_SUCCESS_STATUS && status < FIRST_REDIRECT_STATUS;
}

function shapeFaultOf(status: number): GatewayFault {
  return isSuccessStatus(status) ? 'malformed' : 'rejected';
}

export function shapeFault(exchange: RelayExchange): GatewayFault | null {
  if (exchange.kind === 'fault' || isEnvelope(exchange.body)) return null;
  return shapeFaultOf(exchange.status);
}

function open(exchange: RelayExchange): Opened | Unavailable {
  if (exchange.kind === 'fault') return { kind: 'unavailable', fault: exchange.fault };
  if (isEnvelope(exchange.body)) return { status: exchange.status, envelope: exchange.body };
  return { kind: 'unavailable', fault: shapeFaultOf(exchange.status) };
}

function isUnavailable(opened: Opened | Unavailable): opened is Unavailable {
  return 'kind' in opened;
}

function categoryOf(value: string | undefined): RefusalCategory {
  return REFUSAL_CATEGORIES.includes(value ?? '') ? (value as RefusalCategory) : FALLBACK_CATEGORY;
}

function refusalOf(envelope: BookingRelayResponseDto): Refusal | null {
  if (typeof envelope.code !== 'string') return null;
  const refusal: Refusal = { category: categoryOf(envelope.category), code: envelope.code };
  if (typeof envelope.detail === 'string') refusal.detail = envelope.detail;
  return refusal;
}

function codeAndDetail(envelope: BookingRelayResponseDto): { code?: string; detail?: string } {
  const out: { code?: string; detail?: string } = {};
  if (typeof envelope.code === 'string') out.code = envelope.code;
  if (typeof envelope.detail === 'string') out.detail = envelope.detail;
  return out;
}

function isTimeout(status: number): boolean {
  return status === STATUS_GATEWAY_TIMEOUT;
}

export function decodeHold(exchange: RelayExchange): HoldResult {
  const opened = open(exchange);
  if (isUnavailable(opened)) return opened;
  const { status, envelope } = opened;
  if (envelope.ok) return typeof envelope.cart_url === 'string' ? { kind: 'held', cartUrl: envelope.cart_url } : MALFORMED;
  const refusal = refusalOf(envelope);
  if (refusal === null) return MALFORMED;
  if (!isTimeout(status)) return { kind: 'blocked', refusal };
  return typeof envelope.cart_url === 'string' ? { kind: 'timed_out', refusal, cartUrl: envelope.cart_url } : { kind: 'timed_out', refusal };
}

export function decodeLogin(exchange: RelayExchange): LoginResult {
  const opened = open(exchange);
  if (isUnavailable(opened)) return opened;
  const { status, envelope } = opened;
  if (envelope.ok) return { kind: 'signed_in' };
  if (envelope.code === MFA_REQUIRED_CODE) {
    if (typeof envelope.challenge_id !== 'string') return MALFORMED;
    return typeof envelope.expires_at === 'string'
      ? { kind: 'mfa_required', challengeId: envelope.challenge_id, expiresAt: envelope.expires_at }
      : { kind: 'mfa_required', challengeId: envelope.challenge_id };
  }
  const refusal = refusalOf(envelope);
  if (refusal === null) return MALFORMED;
  return isTimeout(status) ? { kind: 'timed_out', refusal } : { kind: 'failed', refusal };
}

export function decodeAction(exchange: RelayExchange): ActionResult {
  const opened = open(exchange);
  if (isUnavailable(opened)) return opened;
  const { status, envelope } = opened;
  if (envelope.ok) return { kind: 'ok' };
  const refusal = refusalOf(envelope);
  if (refusal === null) return MALFORMED;
  return isTimeout(status) ? { kind: 'timed_out', refusal } : { kind: 'failed', refusal };
}

export function decodeHealth(exchange: RelayExchange): SessionHealth {
  const opened = open(exchange);
  if (isUnavailable(opened)) return { kind: 'unavailable' };
  const { envelope } = opened;
  switch (envelope.health) {
    case HEALTH_ACTIVE:
      return { kind: 'active' };
    case HEALTH_NEVER_LOGGED_IN:
      return { kind: 'never_logged_in' };
    case HEALTH_INACTIVE:
      return { kind: 'inactive', ...(typeof envelope.code === 'string' ? { code: envelope.code } : {}) };
    case HEALTH_CHECK_FAILED:
      return { kind: 'check_failed', ...codeAndDetail(envelope) };
    case HEALTH_UNAVAILABLE:
    default:
      return { kind: 'unavailable', ...codeAndDetail(envelope) };
  }
}

export function decodeSignOut(exchange: RelayExchange): SignOutResult {
  const opened = open(exchange);
  if (isUnavailable(opened)) return opened;
  const { status, envelope } = opened;
  const signedOut = envelope.signed_out === true;
  if (envelope.profile_destroyed === true) return { kind: 'wiped', signedOut };
  const refusal = envelope.ok ? null : refusalOf(envelope);
  if (refusal === null) return MALFORMED;
  return isTimeout(status) ? { kind: 'timed_out', refusal } : { kind: 'failed', signedOut, refusal };
}
