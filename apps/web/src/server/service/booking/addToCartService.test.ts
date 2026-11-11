import { shippedTenantRegistry } from '@roadtrip/domain';
import { expect, test, vi } from 'vitest';
import type { RelayResult } from '../../client/bookingRelay';
import type { AppUserRow } from '../../repo/appUserRepo';
import type { BookableCampsite } from '../../repo/campsiteCatalogRepo';
import { addToCart, type AddToCartDeps } from './addToCartService';

const USER: AppUserRow = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system' };
const NOW = Date.parse('2026-12-01T12:00:00Z');
const FRESHNESS_MS = 300_000;
const CAMPSITE: BookableCampsite = {
  id: 55n,
  dataProvider: 'campflare',
  dataProviderRef: 'site-55',
  bookingProvider: 'recgov',
  bookingProviderRef: '300',
  bookingAliases: [],
  campground: { dataProvider: 'campflare', dataProviderRef: 'cg-1', bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] },
};
const BODY = { campsite_id: 55, start_date: '2026-12-10', end_date: '2026-12-11' };
const HELD: RelayResult = { ok: true, status: 200, answer: { ok: true, cart_url: 'https://www.recreation.gov/cart' } };

function cell(status: string, ageMs: number, date = '2026-12-10', ref = 'site-55') {
  return { campsite: { data_provider: 'campflare', data_provider_ref: ref }, date, status, observed_at: new Date(NOW - ageMs).toISOString() };
}

function deps(overrides: Partial<AddToCartDeps> = {}): AddToCartDeps {
  return {
    tenants: shippedTenantRegistry(),
    freshnessMaxAgeMs: FRESHNESS_MS,
    now: () => NOW,
    campsite: vi.fn().mockResolvedValue(CAMPSITE),
    hasCredentials: vi.fn().mockResolvedValue(true),
    storedCells: vi.fn().mockResolvedValue([]),
    relay: vi.fn().mockResolvedValue(HELD),
    ...overrides,
  };
}

test('a held site answers the cart and the booking site', async () => {
  const relay = vi.fn().mockResolvedValue(HELD);
  expect(await addToCart(USER, BODY, deps({ relay }))).toEqual({
    status: 200,
    body: { status: 'completed', cart_url: 'https://www.recreation.gov/cart', provider: 'recgov', provider_display: 'Recreation.gov' },
  });
  expect(relay).toHaveBeenCalledWith('atc', { profile_id: 'n:7', vendor_site_id: '300', start_date: '2026-12-10', end_date: '2026-12-11' });
});

test.each([[{}], [{ campsite_id: 'x', start_date: '2026-12-10', end_date: '2026-12-11' }], [{ campsite_id: 55, start_date: 'soon', end_date: '2026-12-11' }]])(
  'a malformed body %j is 400 invalid_body',
  async (body) => {
    expect(await addToCart(USER, body, deps())).toMatchObject({ status: 400, body: { error: 'invalid_body' } });
  },
);

test('a quoted campsite id still decodes', async () => {
  expect((await addToCart(USER, { ...BODY, campsite_id: '55' }, deps())).status).toBe(200);
});

test('an empty window is 400 invalid_window before any lookup', async () => {
  const campsite = vi.fn();
  expect(await addToCart(USER, { ...BODY, end_date: '2026-12-10' }, deps({ campsite }))).toEqual({ status: 400, body: { error: 'invalid_window' } });
  expect(campsite).not.toHaveBeenCalled();
});

test('an unknown or non-rec.gov campsite is 422 unsupported_target', async () => {
  expect(await addToCart(USER, BODY, deps({ campsite: vi.fn().mockResolvedValue(null) }))).toEqual({
    status: 422,
    body: { error: 'unsupported_target' },
  });
  const campflare = { ...CAMPSITE, bookingProvider: 'campflare', bookingProviderRef: 'x' };
  expect((await addToCart(USER, BODY, deps({ campsite: vi.fn().mockResolvedValue(campflare) }))).status).toBe(422);
});

test('no stored credentials is 403 credentials_required, naming the booking site', async () => {
  expect(await addToCart(USER, BODY, deps({ hasCredentials: vi.fn().mockResolvedValue(false) }))).toEqual({
    status: 403,
    body: { error: 'credentials_required', provider: 'recgov', provider_display: 'Recreation.gov' },
  });
});

test('a night seen taken within the freshness window is 409 not_available, and no hold is tried', async () => {
  const relay = vi.fn();
  const storedCells = vi.fn().mockResolvedValue([cell('reserved', 4 * 60_000)]);
  expect(await addToCart(USER, BODY, deps({ relay, storedCells }))).toMatchObject({ status: 409, body: { error: 'not_available' } });
  expect(relay).not.toHaveBeenCalled();
  expect(storedCells).toHaveBeenCalledWith({ data_provider: 'campflare', data_provider_ref: 'cg-1' }, '2026-12-10', '2026-12-11');
});

test('stale, available, other-site and checkout-night cells are not evidence', async () => {
  const storedCells = vi.fn().mockResolvedValue([
    cell('reserved', 6 * 60_000),
    cell('available', 0),
    cell('reserved', 0, '2026-12-10', 'site-56'),
    cell('reserved', 0, '2026-12-11'),
  ]);
  expect((await addToCart(USER, BODY, deps({ storedCells }))).status).toBe(200);
});

test('a failed stored read lets the hold through', async () => {
  expect((await addToCart(USER, BODY, deps({ storedCells: vi.fn().mockResolvedValue(null) }))).status).toBe(200);
});

test.each([
  ['caller_action', 403],
  ['retry_later', 409],
  ['upstream', 502],
])('a %s refusal from the hold is %i with its code', async (category, status) => {
  const relay = vi.fn().mockResolvedValue({ ok: true, status: 200, answer: { ok: false, code: 'profile_busy', category, detail: 'd' } });
  expect(await addToCart(USER, BODY, deps({ relay }))).toEqual({
    status,
    body: { error: 'profile_busy', detail: 'd', provider: 'recgov', provider_display: 'Recreation.gov' },
  });
});

test('a relay timeout is 409 and says the cart may hold the site', async () => {
  const relay = vi.fn().mockResolvedValue({
    ok: true,
    status: 504,
    answer: { ok: false, code: 'relay_timeout', category: 'retry_later', detail: 'the hold may still finish; check the cart' },
  });
  expect(await addToCart(USER, BODY, deps({ relay }))).toMatchObject({ status: 409, body: { error: 'relay_timeout' } });
});

test('an unreachable or unconfigured relay is 503 booking_unavailable', async () => {
  expect(await addToCart(USER, BODY, deps({ relay: vi.fn().mockResolvedValue({ ok: false, code: 'relay_not_json' }) }))).toMatchObject({
    status: 503,
    body: { error: 'booking_unavailable' },
  });
  expect(await addToCart(USER, BODY, deps({ relay: null }))).toMatchObject({ status: 503, body: { error: 'booking_unavailable' } });
});
