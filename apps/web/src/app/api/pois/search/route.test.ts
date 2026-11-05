import { beforeEach, expect, test, vi } from 'vitest';

const searchPois = vi.fn();
vi.mock('@/server/service/poiService', () => ({ searchPois }));

const { GET } = await import('./route');

const get = (query: string) => GET(new Request(`http://localhost/api/pois/search?${query}`));

beforeEach(() => {
  searchPois.mockReset();
  searchPois.mockResolvedValue({ results: [] });
});

test('passes the trimmed query, the default limit and split categories', async () => {
  const response = await get('q=%20mahood%20&categories=campground,%20supercharger&categories=');
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ results: [] });
  expect(searchPois).toHaveBeenCalledWith('mahood', 10, ['campground', 'supercharger']);
});

test.each([
  ['0', 1],
  ['99', 25],
  ['abc', 10],
  ['7', 7],
])('limit %s is clamped to 1..25 as %d', async (limit, expected) => {
  await get(`q=ab&limit=${limit}`);
  expect(searchPois).toHaveBeenCalledWith('ab', expected, []);
});
