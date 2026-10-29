import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, expect, test, vi } from 'vitest';
import { DEFAULT_SEARCH_BBOX, bboxToPolygonGeoJson } from '@/server/boundary';
import type { CampgroundSearchDto, CampgroundSummaryDto } from '@/server/dto';

const searchCampgrounds = vi.fn();

class StubInvalidBoundaryError extends Error {}

vi.mock('@/server/service/campgroundService', () => ({
  searchCampgrounds,
  InvalidBoundaryError: StubInvalidBoundaryError,
}));

const { default: SearchPage } = await import('./page');

const ALERT_MARKER = 'role="alert"';
const SEARCH_CAP = 200;
const OVER_CAP_TOTAL = 250;
const HOSTILE_NAME = '<script>alert(1)</script>';
const HOSTILE_REGION = '"><img src=x onerror=alert(2)>';
const HOSTILE_BBOX = '"><script>alert(3)</script>';
const ANTIMERIDIAN_BBOX = '170,0,-170,10';

function summary(overrides: Partial<CampgroundSummaryDto> = {}): CampgroundSummaryDto {
  return {
    poiId: '1002',
    campgroundId: '2001',
    name: 'Near Campground',
    lng: -120,
    lat: 39.05,
    region: null,
    amenities: null,
    siteSummary: null,
    decodeErrors: [],
    ...overrides,
  };
}

function searchResult(
  campgrounds: CampgroundSummaryDto[],
  overrides: Partial<CampgroundSearchDto> = {},
): CampgroundSearchDto {
  return {
    poiIds: campgrounds.map((campground) => campground.poiId),
    totalInBoundary: campgrounds.length,
    totalMatching: campgrounds.length,
    truncated: false,
    campgrounds,
    ...overrides,
  };
}

async function render(query: Record<string, string | string[] | undefined>): Promise<string> {
  return renderToStaticMarkup(await SearchPage({ searchParams: Promise.resolve(query) }));
}

beforeEach(() => {
  searchCampgrounds.mockReset();
  searchCampgrounds.mockResolvedValue(searchResult([summary()]));
});

test('a missing bbox searches the default box', async () => {
  const markup = await render({});
  expect(searchCampgrounds).toHaveBeenCalledTimes(1);
  expect(searchCampgrounds).toHaveBeenCalledWith(bboxToPolygonGeoJson(DEFAULT_SEARCH_BBOX));
  expect(markup).toContain('Near Campground');
  expect(markup).not.toContain(ALERT_MARKER);
  expect(markup).toContain(`value="${DEFAULT_SEARCH_BBOX}"`);
});

test.each([
  ['garbage', 'garbage'],
  ['empty', ''],
  ['repeated', ['-120.4,38.7,-119.6,39.4', '1,2,3,4']],
  ['wrong arity', '1,2,3'],
  ['out of range', '0,-91,10,10'],
])('a %s bbox renders the alert and never reaches the service', async (_label, bbox) => {
  const markup = await render({ bbox });
  expect(markup).toContain(ALERT_MARKER);
  expect(markup).toContain('west,south,east,north');
  expect(searchCampgrounds).not.toHaveBeenCalled();
});

test('a box crossing the antimeridian renders its own alert instead of an empty list', async () => {
  const markup = await render({ bbox: ANTIMERIDIAN_BBOX });
  expect(markup).toContain(ALERT_MARKER);
  expect(markup).toContain('antimeridian');
  expect(markup).not.toContain('No campgrounds matched');
  expect(searchCampgrounds).not.toHaveBeenCalled();
});

test('a box over the area limit renders its own alert and never reaches the service', async () => {
  const markup = await render({ bbox: '-130,25,-65,50' });
  expect(markup).toContain(ALERT_MARKER);
  expect(markup).toContain('square degrees');
  expect(markup).not.toContain('No campgrounds matched');
  expect(searchCampgrounds).not.toHaveBeenCalled();
});

test('a boundary the service rejects renders the alert, not an error', async () => {
  searchCampgrounds.mockRejectedValue(new StubInvalidBoundaryError());
  const markup = await render({ bbox: '0,0,1,1' });
  expect(markup).toContain(ALERT_MARKER);
});

test('any other service failure propagates', async () => {
  searchCampgrounds.mockRejectedValue(new Error('connection refused'));
  await expect(render({ bbox: '0,0,1,1' })).rejects.toThrow('connection refused');
});

test('database strings are HTML-escaped', async () => {
  searchCampgrounds.mockResolvedValue(
    searchResult([summary({ name: HOSTILE_NAME, region: HOSTILE_REGION })]),
  );
  const markup = await render({ bbox: '0,0,1,1' });
  expect(markup).not.toContain('<script>');
  expect(markup).not.toContain('<img');
  expect(markup).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
});

test('the reflected bbox is HTML-escaped', async () => {
  const markup = await render({ bbox: HOSTILE_BBOX });
  expect(markup).not.toContain('<script>');
  expect(markup).toContain('&quot;&gt;&lt;script&gt;');
});

test('past the cap it reports the total and how many are shown', async () => {
  const shown = Array.from({ length: SEARCH_CAP }, (_, index) =>
    summary({ poiId: String(index + 1), name: `Camp ${index + 1}` }),
  );
  searchCampgrounds.mockResolvedValue(
    searchResult(shown, { totalMatching: OVER_CAP_TOTAL, truncated: true }),
  );
  const markup = await render({ bbox: '0,0,1,1' });
  expect(markup).toContain('250 matching campgrounds, showing the nearest 200');
});

test('an untruncated result states only the count', async () => {
  const markup = await render({ bbox: '0,0,1,1' });
  expect(markup).toContain('1 matching campground<');
  expect(markup).not.toContain('showing the nearest');
});

test('site counts are pluralised', async () => {
  searchCampgrounds.mockResolvedValue(
    searchResult([
      summary({ siteSummary: { siteTotal: 1, siteCounts: { tent: 1 }, maxPeople: 4 } }),
      summary({
        poiId: '1003',
        siteSummary: { siteTotal: 3, siteCounts: { tent: 3 }, maxPeople: 4 },
      }),
    ]),
  );
  const markup = await render({ bbox: '0,0,1,1' });
  expect(markup).toContain(', 1 site<');
  expect(markup).toContain(', 3 sites<');
  expect(markup).not.toContain('1 sites');
});

test('an empty result says nothing matched and renders no list', async () => {
  searchCampgrounds.mockResolvedValue(searchResult([]));
  const markup = await render({ bbox: '0,0,1,1' });
  expect(markup).toContain('No campgrounds matched');
  expect(markup).not.toContain('<ul>');
  expect(markup).not.toContain(ALERT_MARKER);
});
