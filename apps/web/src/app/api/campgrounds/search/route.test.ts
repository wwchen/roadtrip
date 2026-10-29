import { beforeEach, expect, test, vi } from 'vitest';
import type { CampgroundSearchDto } from '@/server/dto';

const searchCampgrounds = vi.fn();

class StubInvalidBoundaryError extends Error {}

vi.mock('@/server/service/campgroundService', () => ({
  searchCampgrounds,
  InvalidBoundaryError: StubInvalidBoundaryError,
}));

const { GET } = await import('./route');

const SEARCH_URL = 'http://localhost/api/campgrounds/search';
const SERVER_TIMING_PATTERN = /^db;dur=\d+\.\d$/;
const STATUS_OK = 200;
const STATUS_BAD_REQUEST = 400;
const EMPTY_RESULT: CampgroundSearchDto = {
  poiIds: [],
  totalInBoundary: 0,
  totalMatching: 0,
  truncated: false,
  campgrounds: [],
};

beforeEach(() => {
  searchCampgrounds.mockReset();
  searchCampgrounds.mockResolvedValue(EMPTY_RESULT);
});

test('a missing bbox searches the default and reports database time', async () => {
  const response = await GET(new Request(SEARCH_URL));
  expect(response.status).toBe(STATUS_OK);
  expect(response.headers.get('Server-Timing')).toMatch(SERVER_TIMING_PATTERN);
  expect(await response.json()).toEqual(EMPTY_RESULT);
  expect(searchCampgrounds).toHaveBeenCalledTimes(1);
});

test('Server-Timing covers the service call and not the serialization', async () => {
  const SERVICE_DELAY_MS = 50;
  const MAX_DURATION_MS = 500;
  searchCampgrounds.mockImplementation(
    () => new Promise((resolve) => setTimeout(() => resolve(EMPTY_RESULT), SERVICE_DELAY_MS)),
  );
  const response = await GET(new Request(SEARCH_URL));
  const duration = Number(response.headers.get('Server-Timing')?.split('=')[1]);
  expect(duration).toBeGreaterThanOrEqual(SERVICE_DELAY_MS - 5);
  expect(duration).toBeLessThan(MAX_DURATION_MS);
});

test.each(['garbage', '', '1,2,3', '0,-91,10,10', '170,0,-170,10'])(
  'a malformed bbox %j is a 400 and never reaches the service',
  async (bbox) => {
    const response = await GET(new Request(`${SEARCH_URL}?bbox=${encodeURIComponent(bbox)}`));
    expect(response.status).toBe(STATUS_BAD_REQUEST);
    expect(await response.json()).toHaveProperty('error');
    expect(searchCampgrounds).not.toHaveBeenCalled();
  },
);

test.each(['-130,25,-65,50', '-180,-90,180,90', '-125,42,-116,49'])(
  'a box over the area limit %j is a 400 that says so and never reaches the service',
  async (bbox) => {
    const response = await GET(new Request(`${SEARCH_URL}?bbox=${bbox}`));
    expect(response.status).toBe(STATUS_BAD_REQUEST);
    expect(((await response.json()) as { error: string }).error).toContain('square degrees');
    expect(searchCampgrounds).not.toHaveBeenCalled();
  },
);

test.each(['-180,90,180,-90', '-180,37,180,0.09', '-130,50,-65,25', '0,6,6,0'])(
  'a latitude-inverted or mixed-extent box %j is a 400 and never reaches the service',
  async (bbox) => {
    const response = await GET(new Request(`${SEARCH_URL}?bbox=${bbox}`));
    expect(response.status).toBe(STATUS_BAD_REQUEST);
    expect(searchCampgrounds).not.toHaveBeenCalled();
  },
);

test('an inverted box says why', async () => {
  const response = await GET(new Request(`${SEARCH_URL}?bbox=-180,90,180,-90`));
  expect(((await response.json()) as { error: string }).error).toContain('south greater than north');
});

test.each([
  'bbox=-120.4,38.7,-119.6,39.4&bbox=1,2,3,4',
  'bbox=0,0,1,1&bbox=0,0,1,1',
  'bbox=-120.4,38.7,-119.6,39.4&bbox=',
])('a repeated bbox (%s) is a 400 on the route exactly as the page treats it', async (query) => {
  const response = await GET(new Request(`${SEARCH_URL}?${query}`));
  expect(response.status).toBe(STATUS_BAD_REQUEST);
  expect(searchCampgrounds).not.toHaveBeenCalled();
});

test('a box exactly at the area limit is searched', async () => {
  const response = await GET(new Request(`${SEARCH_URL}?bbox=0,0,6,6`));
  expect(response.status).toBe(STATUS_OK);
  expect(searchCampgrounds).toHaveBeenCalledTimes(1);
});

test('a boundary the service rejects is a 400', async () => {
  searchCampgrounds.mockRejectedValue(new StubInvalidBoundaryError());
  const response = await GET(new Request(`${SEARCH_URL}?bbox=0,0,1,1`));
  expect(response.status).toBe(STATUS_BAD_REQUEST);
});

test('any other failure propagates rather than being reported as the caller fault', async () => {
  searchCampgrounds.mockRejectedValue(new Error('connection refused'));
  await expect(GET(new Request(`${SEARCH_URL}?bbox=0,0,1,1`))).rejects.toThrow('connection refused');
});
