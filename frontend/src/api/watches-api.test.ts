import { afterEach, describe, expect, test, vi } from 'vitest';
import { HttpError, jsonGetOk } from './http';
import { deleteWatch, listWatches } from './watches-api';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('http.jsonGetOk', () => {
  test('returns parsed JSON on 200', async () => {
    stubFetch(jsonResponse({ ok: 1 }));
    await expect(jsonGetOk('/x')).resolves.toEqual({ ok: 1 });
  });

  test('throws HttpError carrying the body error code on non-2xx', async () => {
    stubFetch(jsonResponse({ error: 'nope' }, 400));
    await expect(jsonGetOk('/x')).rejects.toMatchObject({
      name: 'HttpError',
      status: 400,
      code: 'nope',
    });
  });

  test('leaves the code undefined when the error body is not JSON', async () => {
    stubFetch(textResponse('<html>', 500));
    const error = await jsonGetOk('/x').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HttpError);
    expect((error as HttpError).code).toBeUndefined();
  });
});

describe('watches-api', () => {
  test('listWatches builds a filtered query string', async () => {
    const fetchStub = stubFetch(jsonResponse([]));
    await listWatches({ status: 'active', poiId: 7 });
    expect(fetchStub.last.url).toBe('/api/watches?status=active&poi_id=7');
  });

  test('listWatches omits the query string when unfiltered', async () => {
    const fetchStub = stubFetch(jsonResponse([]));
    await listWatches();
    expect(fetchStub.last.url).toBe('/api/watches');
  });

  // deleteWatch is a POST to /delete, not an HTTP DELETE.
  test('deleteWatch swallows a 404', async () => {
    stubFetch(textResponse('', 404));
    await expect(deleteWatch(5)).resolves.toBeUndefined();
  });

  test('deleteWatch throws on other errors', async () => {
    stubFetch(textResponse('', 500));
    await expect(deleteWatch(5)).rejects.toBeInstanceOf(HttpError);
  });
});
