import { beforeEach, describe, expect, test, vi } from 'vitest';
import type { ActiveUser } from '@/server/service/auth/model';
import { errorAnswer, okAnswer, STATUS_FORBIDDEN, STATUS_OK, STATUS_UNAUTHORIZED, STATUS_UNPROCESSABLE } from './httpAnswer';
import { browserRequest } from '@/server/testing/browserRequest';
import { CROSS_ORIGIN } from './sameOrigin';
import { signedInRoutes, type SignedInInput } from './signedIn';

const currentAppUser = vi.fn<() => Promise<ActiveUser | null>>();
const signedIn = signedInRoutes(currentAppUser);

const NO_STORE = 'private, no-store';
const COOKIE = 'mfa=abc; Path=/api; HttpOnly';
const ENDPOINT = 'https://example.test/api';
const USER: ActiveUser = { id: 7n, email: 'a@b.test', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const OK_BODY = { value: 1 };
const ERROR_CODE = 'bad_thing';
const ERROR_DETAIL = 'why';
const JSON_BODY = { a: 1 };
const UNPARSEABLE = '{not json';

function post(text: string): Request {
  return browserRequest(ENDPOINT, { method: 'POST', body: text });
}

const FORGED = new Request(ENDPOINT, {
  method: 'POST',
  body: '{}',
  headers: { origin: 'https://evil.test', 'sec-fetch-site': 'cross-site', 'content-type': 'application/json' },
});

describe('signedIn', () => {
  beforeEach(() => {
    currentAppUser.mockReset();
    currentAppUser.mockResolvedValue(USER);
  });

  test('an anonymous caller gets 401 unauthenticated, never cached, and the handler is not called', async () => {
    currentAppUser.mockResolvedValue(null);
    const handler = vi.fn();
    const response = await signedIn(handler)(post('{}'));
    expect(response.status).toBe(STATUS_UNAUTHORIZED);
    expect(await response.json()).toEqual({ error: 'unauthenticated' });
    expect(response.headers.get('cache-control')).toBe(NO_STORE);
    expect(handler).not.toHaveBeenCalled();
  });

  test('a write from another origin gets 403 cross_origin, never cached, before the user is looked up', async () => {
    const handler = vi.fn();
    const response = await signedIn(handler)(FORGED);
    expect(response.status).toBe(STATUS_FORBIDDEN);
    expect(await response.json()).toEqual({ error: CROSS_ORIGIN });
    expect(response.headers.get('cache-control')).toBe(NO_STORE);
    expect(currentAppUser).not.toHaveBeenCalled();
    expect(handler).not.toHaveBeenCalled();
  });

  test("the answer's status, body and cookie reach the response, never cached", async () => {
    const ok = await signedIn(async () => okAnswer(OK_BODY, COOKIE))();
    expect(ok.status).toBe(STATUS_OK);
    expect(await ok.json()).toEqual(OK_BODY);
    expect(ok.headers.get('cache-control')).toBe(NO_STORE);
    expect(ok.headers.get('set-cookie')).toBe(COOKIE);

    const plain = await signedIn(async () => okAnswer(OK_BODY))();
    expect(plain.headers.get('cache-control')).toBe(NO_STORE);
    expect(plain.headers.get('set-cookie')).toBeNull();

    const failed = await signedIn(async () => errorAnswer(STATUS_UNPROCESSABLE, ERROR_CODE, ERROR_DETAIL))();
    expect(failed.status).toBe(STATUS_UNPROCESSABLE);
    expect(await failed.json()).toEqual({ error: ERROR_CODE, detail: ERROR_DETAIL });
    expect(failed.headers.get('cache-control')).toBe(NO_STORE);

    const bare = await signedIn(async () => errorAnswer(STATUS_UNPROCESSABLE, ERROR_CODE))();
    expect(await bare.json()).toEqual({ error: ERROR_CODE });
  });

  test('a handler that reads JSON gets the body parsed, and an unreadable one as undefined', async () => {
    const bodies: unknown[] = [];
    const handler = vi.fn(async (input: SignedInInput) => {
      bodies.push(await input.readJson());
      return okAnswer(OK_BODY);
    });
    await signedIn(handler)(post(JSON.stringify(JSON_BODY)));
    await signedIn(handler)(post(UNPARSEABLE));
    await signedIn(handler)(post(''));
    await signedIn(handler)();
    expect(bodies).toEqual([JSON_BODY, undefined, undefined, undefined]);
    expect(handler.mock.calls[0]![0].user).toBe(USER);
  });

  test('a handler that never reads JSON leaves the request body unread', async () => {
    const request = post(JSON.stringify(JSON_BODY));
    const response = await signedIn(async () => okAnswer(OK_BODY))(request);
    expect(response.status).toBe(STATUS_OK);
    expect(request.bodyUsed).toBe(false);
  });

  test('a call with no request still runs the handler', async () => {
    const handler = vi.fn(async (_input: SignedInInput) => okAnswer(OK_BODY));
    const response = await signedIn(handler)();
    expect(response.status).toBe(STATUS_OK);
    expect(handler.mock.calls[0]![0]).toMatchObject({ user: USER, request: undefined });
  });
});
