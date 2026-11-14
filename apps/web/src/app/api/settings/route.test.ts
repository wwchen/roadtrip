import { expect, test, vi } from 'vitest';

const requireAppUser = vi.fn();
const currentSettings = vi.fn();
vi.mock('@/server/auth/currentUser', () => ({ requireAppUser }));
vi.mock('@/server/service/settings/settingsService', () => ({ currentSettings }));
const { GET } = await import('./route');

test('an anonymous caller gets 401', async () => {
  requireAppUser.mockResolvedValue(Response.json({ error: 'unauthenticated' }, { status: 401 }));
  const response = await GET();
  expect(response.status).toBe(401);
  expect(currentSettings).not.toHaveBeenCalled();
});

test('a signed-in caller gets their settings, never cached', async () => {
  const user = { id: 7n };
  requireAppUser.mockResolvedValue(user);
  currentSettings.mockResolvedValue({ booking: { recgov_configured: false } });
  const response = await GET();
  expect(response.status).toBe(200);
  expect(response.headers.get('cache-control')).toBe('private, no-store');
  expect(currentSettings).toHaveBeenCalledWith(user);
});
