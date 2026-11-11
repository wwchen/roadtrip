import { expect, test, vi } from 'vitest';

const requireAppUser = vi.fn();
const service = {
  save: vi.fn(),
  remove: vi.fn(),
  login: vi.fn(),
  completeMfa: vi.fn(),
  verify: vi.fn(),
  status: vi.fn(),
};
vi.mock('@/server/auth/currentUser', () => ({ requireAppUser }));
vi.mock('@/server/service/settings/recgovCredentialService', async (original) => ({
  ...(await original<object>()),
  liveRecgovDeps: () => ({}),
  ...service,
}));

const root = await import('./route');
const loginRoute = await import('./login/route');
const mfaRoute = await import('./login/mfa/route');
const verifyRoute = await import('./verify/route');
const statusRoute = await import('./status/route');

const USER = { id: 7n };
const UNAUTHENTICATED = Response.json({ error: 'unauthenticated' }, { status: 401 });
const request = (method: string, body?: string, cookie?: string) =>
  new Request('http://localhost/api/settings/recgov', {
    method,
    ...(body === undefined ? {} : { body }),
    headers: cookie === undefined ? {} : { cookie },
  });

test('every route answers 401 to an anonymous caller and calls no service', async () => {
  const calls = [
    () => root.PUT(request('PUT', '{}')),
    () => root.DELETE(),
    () => loginRoute.POST(),
    () => mfaRoute.POST(request('POST', '{"code":"1"}')),
    () => verifyRoute.POST(),
    () => statusRoute.GET(request('GET')),
  ];
  for (const call of calls) {
    requireAppUser.mockResolvedValueOnce(UNAUTHENTICATED.clone());
    expect((await call()).status).toBe(401);
  }
  for (const fn of Object.values(service)) expect(fn).not.toHaveBeenCalled();
});

test('a service refusal keeps its status and body', async () => {
  requireAppUser.mockResolvedValue(USER);
  service.save.mockResolvedValue({ status: 502, body: { error: 'recgov_profile_wipe_failed', detail: 'x' } });
  const response = await root.PUT(request('PUT', '{"username":"a"}'));
  expect(response.status).toBe(502);
  expect(await response.json()).toEqual({ error: 'recgov_profile_wipe_failed', detail: 'x' });
  expect(service.save.mock.calls[0]![1]).toEqual({ username: 'a' });
});

test('mfa_required sets the sealed challenge cookie on the login path only', async () => {
  requireAppUser.mockResolvedValue(USER);
  service.login.mockResolvedValue({ status: 200, body: { status: 'mfa_required' }, cookie: { value: 'sealed', maxAgeSeconds: 120 } });
  const response = await loginRoute.POST();
  const cookie = response.headers.get('set-cookie') ?? '';
  expect(cookie).toContain('__Secure-recgov-mfa=sealed');
  expect(cookie).toContain('Path=/api/settings/recgov/login');
  expect(cookie).toContain('Max-Age=120');
  expect(cookie).toContain('HttpOnly');
  expect(cookie).toContain('Secure');
  expect(cookie).toContain('SameSite=Strict');
});

test('the mfa route hands the service the cookie and clears it', async () => {
  requireAppUser.mockResolvedValue(USER);
  service.completeMfa.mockResolvedValue({ status: 200, body: { status: 'ok' }, cookie: 'clear' });
  const response = await mfaRoute.POST(request('POST', '{"code":"123456"}', 'other=1; __Secure-recgov-mfa=sealed-value'));
  expect(service.completeMfa.mock.calls[0]![1]).toEqual({ code: '123456' });
  expect(service.completeMfa.mock.calls[0]![2]).toBe('sealed-value');
  expect(response.headers.get('set-cookie')).toContain('Max-Age=0');
});

test('status reads the cookie and is never cached', async () => {
  requireAppUser.mockResolvedValue(USER);
  service.status.mockResolvedValue({ status: 200, body: { configured: false, session: 'not_configured', mfa_pending: false } });
  const response = await statusRoute.GET(request('GET', undefined, '__Secure-recgov-mfa=v'));
  expect(service.status.mock.calls[0]![1]).toBe('v');
  expect(response.headers.get('cache-control')).toBe('private, no-store');
});

test('routes that reach mini-ca allow the relay its full timeout', () => {
  for (const route of [root, loginRoute, mfaRoute, verifyRoute, statusRoute]) expect(route.maxDuration).toBe(120);
});
