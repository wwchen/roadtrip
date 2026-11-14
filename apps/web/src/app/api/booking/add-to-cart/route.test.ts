import { beforeEach, expect, test, vi } from 'vitest';
import { shippedTenantRegistry } from '@roadtrip/domain';
import { unconfiguredBookingGateway } from '@/server/client/bookingRelay';
import type { BookableCampsite } from '@/server/service/booking/model';
import type { BookingPorts } from '@/server/service/booking/ports';

const NO_STORE = 'private, no-store';
const CAMPSITE: BookableCampsite = {
  id: 55n,
  dataProvider: 'campflare',
  dataProviderRef: 'site-55',
  bookingProvider: 'recgov',
  bookingProviderRef: '300',
  bookingAliases: [],
  campground: { dataProvider: 'campflare', dataProviderRef: 'cg-1', bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] },
};
const USER = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const hold = vi.fn();
const currentAppUser = vi.fn();
const fakePorts: BookingPorts = {
  gateway: { ...unconfiguredBookingGateway, hold },
  vault: { status: async () => ({ kind: 'stored', username: 'rec@example.com' }), open: vi.fn(), store: vi.fn(), rename: vi.fn(), remove: vi.fn() },
  catalog: { bookableCampsite: async () => CAMPSITE },
  stored: { takenIn: async () => false },
  tenants: shippedTenantRegistry(),
};
vi.mock('@/server/auth/currentUser', () => ({ currentAppUser: () => currentAppUser() }));
vi.mock('@/server/wiring/bookingPorts', () => ({ bookingPorts: () => fakePorts }));
const { POST, maxDuration } = await import('./route');

const post = (body: string) => POST(new Request('http://localhost/api/booking/add-to-cart', { method: 'POST', body }));

beforeEach(() => {
  hold.mockReset();
  currentAppUser.mockReset();
});

test('the route allows the relay its full timeout', () => {
  expect(maxDuration).toBe(120);
});

test('an anonymous caller gets 401 and nothing is held', async () => {
  currentAppUser.mockResolvedValue(null);
  expect((await post('{}')).status).toBe(401);
  expect(hold).not.toHaveBeenCalled();
});

test('the answer is never cached', async () => {
  currentAppUser.mockResolvedValue(USER);
  hold.mockResolvedValue({ kind: 'held', cartUrl: 'https://www.recreation.gov/cart' });
  const held = await post('{"campsite_id":55,"start_date":"2026-12-10","end_date":"2026-12-11"}');
  expect(held.status).toBe(200);
  expect(held.headers.get('cache-control')).toBe(NO_STORE);
  currentAppUser.mockResolvedValue(null);
  expect((await post('{}')).headers.get('cache-control')).toBe(NO_STORE);
});
