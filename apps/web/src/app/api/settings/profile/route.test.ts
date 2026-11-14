import { beforeEach, expect, test, vi } from 'vitest';

const currentAppUser = vi.fn();
const notifications = vi.fn();
const saveProfile = vi.fn();
const USER = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
vi.mock('@/server/auth/currentUser', () => ({ currentAppUser }));
vi.mock('@/server/wiring/settingsPorts', () => ({
  settingsPorts: () => ({
    vault: { status: async () => ({ kind: 'none' }) },
    adminEmails: new Set<string>(),
    notifications,
    saveProfile,
  }),
}));
const { PUT } = await import('./route');

const put = (body: string) => PUT(new Request('http://localhost/api/settings/profile', { method: 'PUT', body }));

beforeEach(() => {
  currentAppUser.mockReset();
  notifications.mockReset();
  saveProfile.mockReset();
  notifications.mockResolvedValue({ notificationEmail: USER.email, slackChannel: null, slackConfigured: false, slackTokenHint: null });
  saveProfile.mockImplementation(async (_id, patch) => ({ ...USER, ...patch }));
});

test('an anonymous caller gets 401', async () => {
  currentAppUser.mockResolvedValue(null);
  const response = await put('{}');
  expect(response.status).toBe(401);
  expect(response.headers.get('cache-control')).toBe('private, no-store');
  expect(saveProfile).not.toHaveBeenCalled();
});

test('a refused field is 400 invalid_field with its detail', async () => {
  currentAppUser.mockResolvedValue(USER);
  const response = await put('{"theme":"neon"}');
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'invalid_field', detail: 'theme must be one of light, dark, system' });
  expect(saveProfile).not.toHaveBeenCalled();
});

test('the 400 is never cached', async () => {
  currentAppUser.mockResolvedValue(USER);
  expect((await put('{"theme":"neon"}')).headers.get('cache-control')).toBe('private, no-store');
});

test('an accepted update answers the settings', async () => {
  currentAppUser.mockResolvedValue(USER);
  const response = await put('{"theme":"dark"}');
  expect(response.status).toBe(200);
  expect(await response.json()).toMatchObject({ profile: { theme: 'dark' } });
  expect(saveProfile).toHaveBeenCalledWith(7n, { theme: 'dark' });
});
