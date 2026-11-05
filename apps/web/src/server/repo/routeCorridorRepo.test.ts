import { afterAll, beforeAll, expect, test } from 'vitest';
import { ENABLED_PROVIDERS, FAR_POI_ID, NEAR_POI_ID, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { fetchPoisWithinPolygon } from './poiServingRepo';
import { bufferedCorridor } from './routeCorridorRepo';

const LINE = JSON.stringify({ type: 'LineString', coordinates: [[-120.5, 39.05], [-119.5, 39.05]] });
const MILE = 1609.34;
let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

test('a buffered line contains a POI within the radius and excludes one beyond it', async () => {
  const corridor = await bufferedCorridor(fixture.readDb, LINE, MILE);
  expect(JSON.parse(corridor).type).toMatch(/Polygon/);
  const ids = (await fetchPoisWithinPolygon(fixture.readDb, corridor, ['campground'], ENABLED_PROVIDERS)).map((row) => row.id);
  expect(ids).toContain(NEAR_POI_ID);
  expect(ids).not.toContain(FAR_POI_ID);
});
