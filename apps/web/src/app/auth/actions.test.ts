import { beforeEach, expect, test, vi } from 'vitest';
import { RETRY } from '@/server/auth/authCopy';

const incoming = { current: new Headers() };
const signInWithGoogle = vi.fn();
vi.mock('next/headers', () => ({ headers: async () => incoming.current }));
vi.mock('next/navigation', () => ({ redirect: vi.fn() }));
vi.mock('@/server/auth/neonAuth', () => ({ authPort: () => ({ signInWithGoogle }) }));
const actions = await import('./actions');

const IDLE = { message: null, canResendVerification: false };

beforeEach(() => {
  signInWithGoogle.mockReset();
  signInWithGoogle.mockResolvedValue({ ok: true, url: 'https://accounts.google.test/' });
});

test('a server action with neither an Origin nor a Host header shows the retry message and calls nothing', async () => {
  incoming.current = new Headers();
  expect(await actions.signInWithGoogle(IDLE, new FormData())).toEqual({ message: RETRY, canResendVerification: false });
  expect(signInWithGoogle).not.toHaveBeenCalled();
});

test('without an Origin header the Host and forwarded scheme make the callback origin', async () => {
  incoming.current = new Headers({ host: 'roadtrip.floo.ca', 'x-forwarded-proto': 'https' });
  await actions.signInWithGoogle(IDLE, new FormData());
  expect(signInWithGoogle).toHaveBeenCalledWith('https://roadtrip.floo.ca/', expect.stringMatching(/^https:\/\/roadtrip\.floo\.ca\/auth\/sign-in/));
});
