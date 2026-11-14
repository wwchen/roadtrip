import { shippedTenantRegistry, type TenantRegistry } from '@roadtrip/domain';
import { appDb } from '@/server/appDb';
import { profileIdFor } from '@/server/auth/currentUser';
import { relay, type BookingRelayPath, type RelayResult } from '@/server/client/bookingRelay';
import { AVAILABILITY_STORED_TIMEOUT_MS, config } from '@/server/config';
import type { AddToCartResponseDto, ApiErrorSchema, AvailabilityEgressCellDto, ProviderRefDto } from '@/server/contract';
import { serverDb } from '@/server/db';
import type { AppUserRow } from '@/server/repo/appUserRepo';
import { RECGOV_PROVIDER, findCredentials } from '@/server/repo/bookingCredentialsRepo';
import { findBookableCampsite, type BookableCampsite } from '@/server/repo/campsiteCatalogRepo';
import { configuredEgress } from '@/server/service/availability/availabilityMatrixService';
import { recgovTargetFor } from './bookingTarget';

const STATUS_OK = 200;
const STATUS_BAD_REQUEST = 400;
const STATUS_FORBIDDEN = 403;
const STATUS_CONFLICT = 409;
const STATUS_UNPROCESSABLE = 422;
const STATUS_BAD_GATEWAY = 502;
const STATUS_SERVICE_UNAVAILABLE = 503;
const INVALID_BODY = 'invalid_body';
const INVALID_WINDOW = 'invalid_window';
const UNSUPPORTED_TARGET = 'unsupported_target';
const CREDENTIALS_REQUIRED = 'credentials_required';
const NOT_AVAILABLE = 'not_available';
const BOOKING_UNAVAILABLE = 'booking_unavailable';
const COMPLETED = 'completed';
const BOOKABLE_STATUS = 'available';
const STORED_MODE = 'stored';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const DIGITS = /^\d+$/;
const DEFAULT_FRESHNESS_MAX_AGE_MS = 300_000;
const STATUS_BY_CATEGORY: Readonly<Record<string, number>> = {
  caller_action: STATUS_FORBIDDEN,
  retry_later: STATUS_CONFLICT,
  upstream: STATUS_BAD_GATEWAY,
};

export interface AddToCartDeps {
  tenants: TenantRegistry;
  freshnessMaxAgeMs: number;
  now: () => number;
  campsite: (campsiteId: bigint) => Promise<BookableCampsite | null>;
  hasCredentials: (userId: bigint) => Promise<boolean>;
  storedCells: (campground: ProviderRefDto, startDate: string, endDate: string) => Promise<AvailabilityEgressCellDto[] | null>;
  relay: ((path: BookingRelayPath, body: object) => Promise<RelayResult>) | null;
}

export interface AddToCartAnswer {
  status: number;
  body: AddToCartResponseDto | ApiErrorSchema;
}

interface HoldRequest {
  campsiteId: bigint;
  startDate: string;
  endDate: string;
}

function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && ISO_DATE.test(value) && !Number.isNaN(Date.parse(value));
}

function holdRequestOf(body: unknown): HoldRequest | null {
  if (typeof body !== 'object' || body === null) return null;
  const { campsite_id: rawId, start_date: startDate, end_date: endDate } = body as Record<string, unknown>;
  const idText = typeof rawId === 'number' && Number.isSafeInteger(rawId) ? String(rawId) : typeof rawId === 'string' ? rawId.trim() : '';
  if (!DIGITS.test(idText) || !isIsoDate(startDate) || !isIsoDate(endDate)) return null;
  return { campsiteId: BigInt(idText), startDate, endDate };
}

const refusal = (status: number, error: string, providerDisplay?: string): AddToCartAnswer => ({
  status,
  body: providerDisplay === undefined ? { error } : { error, provider: RECGOV_PROVIDER, provider_display: providerDisplay },
});

async function knownTaken(campsite: BookableCampsite, request: HoldRequest, deps: AddToCartDeps): Promise<boolean> {
  const cells = await deps
    .storedCells({ data_provider: campsite.campground.dataProvider, data_provider_ref: campsite.campground.dataProviderRef }, request.startDate, request.endDate)
    .catch(() => null);
  if (cells === null) return false;
  const freshAfter = deps.now() - deps.freshnessMaxAgeMs;
  return cells.some(
    (cell) =>
      cell.campsite.data_provider === campsite.dataProvider &&
      cell.campsite.data_provider_ref === campsite.dataProviderRef &&
      cell.date >= request.startDate &&
      cell.date < request.endDate &&
      cell.status !== BOOKABLE_STATUS &&
      Date.parse(cell.observed_at) > freshAfter,
  );
}

export async function addToCart(user: AppUserRow, body: unknown, deps: AddToCartDeps): Promise<AddToCartAnswer> {
  const request = holdRequestOf(body);
  if (request === null) return refusal(STATUS_BAD_REQUEST, INVALID_BODY);
  if (request.endDate <= request.startDate) return refusal(STATUS_BAD_REQUEST, INVALID_WINDOW);
  const campsite = await deps.campsite(request.campsiteId);
  const target = campsite === null ? null : recgovTargetFor(campsite, campsite.campground, deps.tenants);
  if (campsite === null || target === null) return refusal(STATUS_UNPROCESSABLE, UNSUPPORTED_TARGET);
  const display = target.providerDisplay;
  if (!(await deps.hasCredentials(user.id))) return refusal(STATUS_FORBIDDEN, CREDENTIALS_REQUIRED, display);
  if (await knownTaken(campsite, request, deps)) return refusal(STATUS_CONFLICT, NOT_AVAILABLE, display);
  if (deps.relay === null) return refusal(STATUS_SERVICE_UNAVAILABLE, BOOKING_UNAVAILABLE, display);
  const result = await deps.relay('atc', {
    profile_id: profileIdFor(user.id),
    vendor_site_id: target.vendorSiteId,
    start_date: request.startDate,
    end_date: request.endDate,
  });
  if (!result.ok) return refusal(STATUS_SERVICE_UNAVAILABLE, BOOKING_UNAVAILABLE, display);
  const answer = result.answer;
  if (answer.ok && answer.cart_url !== undefined) {
    return { status: STATUS_OK, body: { status: COMPLETED, cart_url: answer.cart_url, provider: RECGOV_PROVIDER, provider_display: display } };
  }
  const code = answer.code ?? BOOKING_UNAVAILABLE;
  return {
    status: STATUS_BY_CATEGORY[answer.category ?? ''] ?? STATUS_BAD_GATEWAY,
    body: { error: code, ...(answer.detail === undefined ? {} : { detail: answer.detail }), provider: RECGOV_PROVIDER, provider_display: display },
  };
}

export function liveAddToCartDeps(): AddToCartDeps {
  const booking = config.booking;
  const cipherKey = config.bookingCipherKey;
  const egress = configuredEgress(AVAILABILITY_STORED_TIMEOUT_MS);
  return {
    tenants: shippedTenantRegistry(),
    freshnessMaxAgeMs: booking?.freshnessMaxAgeMs ?? DEFAULT_FRESHNESS_MAX_AGE_MS,
    now: Date.now,
    campsite: async (campsiteId) => findBookableCampsite(await serverDb(), campsiteId),
    hasCredentials: async (userId) => cipherKey !== null && (await findCredentials(appDb(), userId, RECGOV_PROVIDER)) !== null,
    storedCells: async (campground, startDate, endDate) => {
      if (egress === null) return null;
      const result = await egress({ mode: STORED_MODE, campground, start_date: startDate, end_date: endDate });
      return result.kind === 'cells' ? result.body.cells : null;
    },
    relay: booking === null ? null : (path, body) => relay(booking, path, body),
  };
}
