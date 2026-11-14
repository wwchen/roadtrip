import { expect, test, vi } from 'vitest';

const requireAppUser = vi.fn();
const addToCart = vi.fn();
vi.mock('@/server/auth/currentUser', () => ({ requireAppUser }));
vi.mock('@/server/service/booking/addToCartService', () => ({ addToCart, liveAddToCartDeps: () => ({}) }));
const { POST, maxDuration } = await import('./route');

const post = (body: string) => POST(new Request('http://localhost/api/booking/add-to-cart', { method: 'POST', body }));

test('the route allows the relay its full timeout', () => {
  expect(maxDuration).toBe(120);
});

test('an anonymous caller gets 401 and nothing is held', async () => {
  requireAppUser.mockResolvedValue(Response.json({ error: 'unauthenticated' }, { status: 401 }));
  expect((await post('{}')).status).toBe(401);
  expect(addToCart).not.toHaveBeenCalled();
});

test('the service answer is the response', async () => {
  requireAppUser.mockResolvedValue({ id: 7n });
  addToCart.mockResolvedValue({ status: 409, body: { error: 'not_available' } });
  const response = await post('{"campsite_id":55}');
  expect(response.status).toBe(409);
  expect(await response.json()).toEqual({ error: 'not_available' });
  expect(addToCart.mock.calls[0]![1]).toEqual({ campsite_id: 55 });
});
