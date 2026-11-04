import { beforeEach, expect, test, vi } from 'vitest';

const getCampgroundDetails = vi.fn();
vi.mock('@/server/service/campgroundSearchService', () => ({ getCampgroundDetails }));

const { POST } = await import('./route');

const post = (body: string) => POST(new Request('http://localhost/api/campgrounds/details', { method: 'POST', body }));

beforeEach(() => {
  getCampgroundDetails.mockReset();
  getCampgroundDetails.mockResolvedValue({ campgrounds: [] });
});

test('passes the de-duplicated ids to the service', async () => {
  const response = await post('{"campground_ids":[2,1,2]}');
  expect(response.status).toBe(200);
  expect(getCampgroundDetails).toHaveBeenCalledWith([2n, 1n]);
});

test('more than 50 ids is 400 too_many_ids', async () => {
  const response = await post(JSON.stringify({ campground_ids: Array.from({ length: 51 }, (_, i) => i + 1) }));
  expect(response.status).toBe(400);
  expect(await response.json()).toMatchObject({ error: 'too_many_ids' });
  expect(getCampgroundDetails).not.toHaveBeenCalled();
});

test('a body that is not JSON is 400 bad_request', async () => {
  expect((await post('nope')).status).toBe(400);
});
