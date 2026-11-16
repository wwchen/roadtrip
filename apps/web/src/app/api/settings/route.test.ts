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
const { GET } = await import('./route');

beforeEach(() => {
  currentAppUser.mockReset();
  notifications.mockReset();
  saveProfile.mockReset();
});

test('an anonymous caller gets 401', async () => {
  currentAppUser.mockResolvedValue(null);
  const response = await GET();
  expect(response.status).toBe(401);
  expect(response.headers.get('cache-control')).toBe('private, no-store');
  expect(notifications).not.toHaveBeenCalled();
});

test('a signed-in caller gets their settings, never cached', async () => {
  currentAppUser.mockResolvedValue(USER);
  notifications.mockResolvedValue({ notificationEmail: USER.email, slackChannel: null, slackConfigured: false, slackTokenHint: null });
  const response = await GET();
  expect(response.status).toBe(200);
  expect(response.headers.get('cache-control')).toBe('private, no-store');
  expect(await response.json()).toMatchObject({ booking: { recgov_configured: false } });
  expect(notifications).toHaveBeenCalledWith(7n, 'camper@example.com');
});
