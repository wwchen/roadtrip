import { beforeEach, expect, test, vi } from 'vitest';

const authPort = vi.fn();
const linkedAppUser = vi.fn();
vi.mock('@/server/auth/neonAuth', () => ({ authPort }));
vi.mock('@/server/appDb', () => ({ appDb: vi.fn(() => ({})) }));
vi.mock('@/server/repo/appUserRepo', async (original) => ({
  ...(await original<typeof import('@/server/repo/appUserRepo')>()),
  linkedAppUser,
}));
const { GET } = await import('./route');

beforeEach(() => {
  authPort.mockReset();
  linkedAppUser.mockReset();
});

test('answers 200 with no identity provider', async () => {
  authPort.mockReturnValue(null);
  const response = await GET();
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ authenticated: false, auth_enabled: false, auth_embedded: false });
});

test('answers the signed-in user', async () => {
  authPort.mockReturnValue({
    currentUser: async () => ({ id: 'neon-1', email: 'a@example.com', name: null, emailVerified: true }),
  });
  linkedAppUser.mockResolvedValue({ id: 7n, email: 'a@example.com', emailVerified: true, displayName: null, theme: 'system' });
  const response = await GET();
  expect(response.status).toBe(200);
  expect(await response.json()).toMatchObject({ authenticated: true, user: { id: 7, email: 'a@example.com' } });
});
