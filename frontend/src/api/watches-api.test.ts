import { afterEach, describe, expect, test, vi } from 'vitest';
import { HttpError, jsonGetOk } from './http';
import { deleteWatch, listWatches } from './watches-api';

afterEach(() => vi.unstubAllGlobals());

function stubFetch(response: Response) {
  const fetchMock = vi.fn((..._args: unknown[]) => Promise.resolve(response));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('http.jsonGetOk', () => {
  test('returns parsed JSON on 200', async () => {
    stubFetch(new Response(JSON.stringify({ ok: 1 }), { status: 200 }));
    await expect(jsonGetOk('/x')).resolves.toEqual({ ok: 1 });
  });

  test('throws HttpError carrying the body error code on non-2xx', async () => {
    stubFetch(new Response(JSON.stringify({ error: 'nope' }), { status: 400 }));
    await expect(jsonGetOk('/x')).rejects.toMatchObject({
      name: 'HttpError',
      status: 400,
      code: 'nope',
    });
  });
});

describe('watches-api', () => {
  test('listWatches builds a filtered query string', async () => {
    const fetchMock = stubFetch(new Response('[]', { status: 200 }));
    await listWatches({ status: 'active', poiId: 7 });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/watches?status=active&poi_id=7');
  });

  test('deleteWatch swallows a 404', async () => {
    stubFetch(new Response('', { status: 404 }));
    await expect(deleteWatch(5)).resolves.toBeUndefined();
  });

  test('deleteWatch throws on other errors', async () => {
    stubFetch(new Response('', { status: 500 }));
    await expect(deleteWatch(5)).rejects.toBeInstanceOf(HttpError);
  });
});
