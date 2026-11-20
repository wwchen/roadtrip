import { shippedTenantRegistry } from '@roadtrip/domain';
import { expect, test, vi } from 'vitest';
import type { AvailabilityStatusDto } from '../../contract';
import { storedAvailability } from '../../client/storedAvailability';
import type { EgressFetch } from '../../client/availabilityEgress';
import { unconfiguredBookingGateway } from '../../client/bookingRelay';
import { addToCartAnswer, parseAddToCart } from '../../mapper/http/addToCartHttp';
import type { ActiveUser } from '../auth/model';
import { addToCart } from './addToCartController';
import type { BookableCampsite, GatewayFault, HoldResult } from './model';
import type { BookingPorts } from './ports';

const USER: ActiveUser = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const NOW = Date.parse('2026-12-01T12:00:00Z');
const FRESHNESS_MS = 300_000;
const CART_URL = 'https://www.recreation.gov/cart';
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
const HELD: HoldResult = { kind: 'held', cartUrl: CART_URL };
const FAULTS: GatewayFault[] = ['not_configured', 'unanswered', 'unreachable', 'unauthorized', 'redirected', 'malformed', 'rejected'];

function cell(status: AvailabilityStatusDto, ageMs: number, date = '2026-12-10', ref = 'site-55') {
  return { campsite: { data_provider: 'campflare', data_provider_ref: ref }, date, status, observed_at: new Date(NOW - ageMs).toISOString() };
}

function egressOf(cells: ReturnType<typeof cell>[] | null): EgressFetch {
  return vi.fn(async () =>
    cells === null
      ? { kind: 'error' as const, status: 503, code: 'x', body: null }
      : { kind: 'cells' as const, body: { start_date: '2026-12-10', end_date: '2026-12-11', campsites: [], cells, from_cache: false } },
  );
}

function ports(overrides: Partial<BookingPorts> = {}): BookingPorts {
  return {
    gateway: { ...unconfiguredBookingGateway, hold: vi.fn().mockResolvedValue(HELD) },
    vault: {
      status: vi.fn().mockResolvedValue({ kind: 'stored', username: 'rec@example.com' }),
      open: vi.fn(),
      store: vi.fn(),
      rename: vi.fn(),
      remove: vi.fn(),
    },
    catalog: { bookableCampsite: vi.fn().mockResolvedValue(CAMPSITE) },
    stored: storedAvailability(egressOf([]), { now: () => NOW }, FRESHNESS_MS),
    tenants: shippedTenantRegistry(),
    ...overrides,
  };
}

async function run(body: unknown, withPorts: BookingPorts) {
  const command = parseAddToCart(body);
  if (command.kind === 'invalid') return command.answer;
  return addToCartAnswer(await addToCart(USER, command.value, withPorts));
}

test('a held site answers the cart and the booking site', async () => {
  const p = ports();
  expect(await run(BODY, p)).toEqual({
    status: 200,
    body: { status: 'completed', cart_url: CART_URL, provider: 'recgov', provider_display: 'Recreation.gov' },
  });
  expect(p.gateway.hold).toHaveBeenCalledWith(7n, '300', { startDate: '2026-12-10', endDate: '2026-12-11' });
});

test.each([[{}], [{ campsite_id: 'x', start_date: '2026-12-10', end_date: '2026-12-11' }], [{ campsite_id: 55, start_date: 'soon', end_date: '2026-12-11' }]])(
  'a malformed body %j is 400 invalid_body',
  async (body) => {
    expect(await run(body, ports())).toMatchObject({ status: 400, body: { error: 'invalid_body' } });
  },
);

test('a quoted campsite id still decodes', async () => {
  expect((await run({ ...BODY, campsite_id: '55' }, ports())).status).toBe(200);
});

test('an empty window is 400 invalid_window before any lookup', async () => {
  const p = ports();
  expect(await run({ ...BODY, end_date: '2026-12-10' }, p)).toEqual({ status: 400, body: { error: 'invalid_window' } });
  expect(p.catalog.bookableCampsite).not.toHaveBeenCalled();
});

test('an unknown or non-rec.gov campsite is 422 unsupported_target', async () => {
  expect(await run(BODY, ports({ catalog: { bookableCampsite: vi.fn().mockResolvedValue(null) } }))).toEqual({
    status: 422,
    body: { error: 'unsupported_target' },
  });
  const campflare = { ...CAMPSITE, bookingProvider: 'campflare', bookingProviderRef: 'x' };
  expect((await run(BODY, ports({ catalog: { bookableCampsite: vi.fn().mockResolvedValue(campflare) } }))).status).toBe(422);
});

test('no stored credentials is 403 credentials_required, naming the booking site', async () => {
  const vault = { ...ports().vault, status: vi.fn().mockResolvedValue({ kind: 'none' }) };
  expect(await run(BODY, ports({ vault }))).toEqual({
    status: 403,
    body: { error: 'credentials_required', provider: 'recgov', provider_display: 'Recreation.gov' },
  });
});

test('a locked vault is 403 credentials_required', async () => {
  const p = ports({ vault: { ...ports().vault, status: vi.fn().mockResolvedValue({ kind: 'locked', username: 'rec@example.com' }) } });
  expect(await run(BODY, p)).toEqual({
    status: 403,
    body: { error: 'credentials_required', provider: 'recgov', provider_display: 'Recreation.gov' },
  });
  expect(p.gateway.hold).not.toHaveBeenCalled();
});

test('a night seen taken within the freshness window is 409 not_available, and no hold is tried', async () => {
  const egress = egressOf([cell('reserved', 4 * 60_000)]);
  const p = ports({ stored: storedAvailability(egress, { now: () => NOW }, FRESHNESS_MS) });
  expect(await run(BODY, p)).toMatchObject({ status: 409, body: { error: 'not_available' } });
  expect(p.gateway.hold).not.toHaveBeenCalled();
  expect(egress).toHaveBeenCalledWith({
    mode: 'stored',
    campground: { data_provider: 'campflare', data_provider_ref: 'cg-1' },
    start_date: '2026-12-10',
    end_date: '2026-12-11',
  });
});

test('stale, available, other-site and checkout-night cells are not evidence', async () => {
  const egress = egressOf([
    cell('reserved', 6 * 60_000),
    cell('available', 0),
    cell('reserved', 0, '2026-12-10', 'site-56'),
    cell('reserved', 0, '2026-12-11'),
  ]);
  expect((await run(BODY, ports({ stored: storedAvailability(egress, { now: () => NOW }, FRESHNESS_MS) }))).status).toBe(200);
});

test('a failed stored read lets the hold through', async () => {
  expect((await run(BODY, ports({ stored: storedAvailability(egressOf(null), { now: () => NOW }, FRESHNESS_MS) }))).status).toBe(200);
});

test.each([
  ['caller_action', 403],
  ['retry_later', 409],
  ['upstream', 502],
] as const)('a %s refusal from the hold is %i with its code', async (category, status) => {
  const hold = vi.fn().mockResolvedValue({ kind: 'blocked', refusal: { category, code: 'profile_busy', detail: 'd' } });
  expect(await run(BODY, ports({ gateway: { ...unconfiguredBookingGateway, hold } }))).toEqual({
    status,
    body: { error: 'profile_busy', detail: 'd', provider: 'recgov', provider_display: 'Recreation.gov' },
  });
});

test('a relay timeout is 409 and says the cart may hold the site', async () => {
  const hold = vi.fn().mockResolvedValue({
    kind: 'timed_out',
    refusal: { category: 'retry_later', code: 'relay_timeout', detail: 'the hold may still finish; check the cart' },
  });
  expect(await run(BODY, ports({ gateway: { ...unconfiguredBookingGateway, hold } }))).toMatchObject({ status: 409, body: { error: 'relay_timeout' } });
});

test('an unreachable or unconfigured relay is 503 and names which', async () => {
  const malformed = vi.fn().mockResolvedValue({ kind: 'unavailable', fault: 'malformed' });
  expect(await run(BODY, ports({ gateway: { ...unconfiguredBookingGateway, hold: malformed } }))).toMatchObject({
    status: 503,
    body: { error: 'relay_malformed' },
  });
  expect(await run(BODY, ports({ gateway: unconfiguredBookingGateway }))).toMatchObject({ status: 503, body: { error: 'relay_not_configured' } });
});

test.each(FAULTS)('a hold result of unavailable answers 503 with its own fault as the code: %s', async (fault) => {
  const hold = vi.fn().mockResolvedValue({ kind: 'unavailable', fault });
  expect(await run(BODY, ports({ gateway: { ...unconfiguredBookingGateway, hold } }))).toEqual({
    status: 503,
    body: { error: `relay_${fault}`, provider: 'recgov', provider_display: 'Recreation.gov' },
  });
});
