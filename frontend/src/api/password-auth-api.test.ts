import { describe, expect, test, vi } from 'vitest';
import {
  beginPasswordLogin,
  completePasswordLogin,
  PasswordAuthError,
} from './password-auth-api';
import { jsonResponse, noContentResponse, textResponse } from '@/test/fetch-stub';

interface FakeFetch {
  fn: typeof fetch;
  calls: { url: string; init: RequestInit }[];
}

function fakeFetch(response: Response): FakeFetch {
  const calls: { url: string; init: RequestInit }[] = [];
  const fn = vi.fn(async (input: unknown, init?: RequestInit): Promise<Response> => {
    calls.push({ url: String(input), init: init ?? {} });
    return response.clone();
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

const bodyOf = (call: { init: RequestInit }): unknown => JSON.parse(String(call.init.body));

describe('beginPasswordLogin', () => {
  test('POSTs return_to and returns the flow material', async () => {
    const material = {
      state: 's',
      nonce: 'n',
      code_challenge: 'c',
      redirect_uri: 'https://app.example.com/auth/callback',
    };
    const stub = fakeFetch(jsonResponse(material));

    const out = await beginPasswordLogin('/watches', { _fetch: stub.fn });

    expect(out).toEqual(material);
    expect(stub.calls[0]?.url).toMatch(/\/auth\/password\/begin/);
    expect(stub.calls[0]?.init.method).toBe('POST');
    expect(bodyOf(stub.calls[0]!)).toEqual({ return_to: '/watches' });
    expect(stub.calls[0]?.init.credentials).toBe('same-origin');
  });
});

describe('completePasswordLogin', () => {
  test('POSTs code+state+return_to and resolves on 204', async () => {
    const stub = fakeFetch(noContentResponse());

    const out = await completePasswordLogin('code-1', 'st-1', '/', { _fetch: stub.fn });

    expect(out).toBeNull();
    expect(bodyOf(stub.calls[0]!)).toEqual({
      code: 'code-1',
      state: 'st-1',
      return_to: '/',
    });
  });

  test('rejects with .code from the error body', async () => {
    const stub = fakeFetch(jsonResponse({ error: 'login_failed' }, 401));

    const error = await completePasswordLogin('bad', 'st', '/', { _fetch: stub.fn }).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(PasswordAuthError);
    expect(error).toBeInstanceOf(Error);
    expect((error as PasswordAuthError).code).toBe('login_failed');
    expect((error as Error).message).toBe('/auth/password/complete: HTTP 401');
  });

  test('rejects with an undefined .code when the error body is not JSON', async () => {
    const stub = fakeFetch(textResponse('gateway timeout', 504));

    const error = await completePasswordLogin('x', 'y', '/', { _fetch: stub.fn }).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(PasswordAuthError);
    expect((error as PasswordAuthError).code).toBeUndefined();
  });
});
