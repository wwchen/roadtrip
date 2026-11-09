import { expect, test, vi } from 'vitest';

vi.mock('@/server/auth/neonAuth', () => ({ neonAuth: vi.fn(() => null) }));
const { GET, POST } = await import('./route');

const request = (method: string) => new Request('http://localhost/api/auth/get-session', { method });

test.each([
  ['GET', GET],
  ['POST', POST],
] as const)('%s answers 404 while sign-in is off', async (method, handler) => {
  const response = await handler(request(method));
  expect(response.status).toBe(404);
  expect(await response.json()).toEqual({ error: 'not_found' });
});
