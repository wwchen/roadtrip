import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, expect, test, vi } from 'vitest';
import type { CampgroundDetailDto } from '@/server/dto';

const getCampgroundDetail = vi.fn();

vi.mock('@/server/service/campgroundService', () => ({ getCampgroundDetail }));

const { default: CampgroundPage } = await import('./page');

const NOT_FOUND_DIGEST = 'NEXT_HTTP_ERROR_FALLBACK;404';
const CAMPGROUND_NAME = 'Near Campground';
const SAFE_RESERVATION_URL = 'https://example.com/reserve';
const HOSTILE_RESERVATION_URL = 'javascript:alert(1)';
const KNOWN_POI_ID = '1002';

const DETAIL: CampgroundDetailDto = {
  poiId: KNOWN_POI_ID,
  campgroundId: '2001',
  dataProvider: 'campflare',
  dataProviderRef: 'ref-2001',
  name: CAMPGROUND_NAME,
  parentName: null,
  kind: null,
  status: null,
  statusDescription: null,
  shortDescription: null,
  mediumDescription: null,
  longDescription: null,
  reservationUrl: null,
  bigRigFriendly: null,
  hasPullThroughSites: null,
  maxRvLength: null,
  maxTrailerLength: null,
  location: null,
  amenities: null,
  price: null,
  contact: null,
  decodeErrors: [],
};

async function render(poiId: string): Promise<string> {
  const element = await CampgroundPage({ params: Promise.resolve({ poiId }) });
  return renderToStaticMarkup(element);
}

beforeEach(() => {
  getCampgroundDetail.mockReset();
  getCampgroundDetail.mockResolvedValue(DETAIL);
});

test.each(['abc', '-1', '0', '1.5', '', '92233720368547758070'])(
  'an unparseable id %j is notFound and never reaches the service',
  async (poiId) => {
    await expect(render(poiId)).rejects.toMatchObject({ digest: NOT_FOUND_DIGEST });
    expect(getCampgroundDetail).not.toHaveBeenCalled();
  },
);

test('an id the service does not know is notFound', async () => {
  getCampgroundDetail.mockResolvedValue(null);
  await expect(render('9999')).rejects.toMatchObject({ digest: NOT_FOUND_DIGEST });
  expect(getCampgroundDetail).toHaveBeenCalledWith(9999n);
});

test('a known id renders the campground', async () => {
  expect(await render(KNOWN_POI_ID)).toContain(CAMPGROUND_NAME);
});

test('a leading-zero id reaches the service as the canonical id', async () => {
  await render('01002');
  expect(getCampgroundDetail).toHaveBeenCalledWith(1002n);
});

test('an http(s) reservation url is rendered as a link', async () => {
  getCampgroundDetail.mockResolvedValue({ ...DETAIL, reservationUrl: SAFE_RESERVATION_URL });
  expect(await render(KNOWN_POI_ID)).toContain(`href="${SAFE_RESERVATION_URL}"`);
});

test('a hostile reservation url is never rendered into an href', async () => {
  getCampgroundDetail.mockResolvedValue({ ...DETAIL, reservationUrl: HOSTILE_RESERVATION_URL });
  const markup = await render(KNOWN_POI_ID);
  expect(markup).not.toContain('javascript:');
  expect(markup).toContain(CAMPGROUND_NAME);
});
