import {
  campgrounds,
  campsites,
  createDb,
  ewkt,
  migrateDb,
  poiCampgrounds,
  pois,
  type Db,
} from '@roadtrip/db';
import { CampsiteKind } from '@roadtrip/domain';
import { withPostgis, type PostgisSession } from '../../../../../scripts/schema-reference.mjs';
import { openServerDb } from '../db';

const CATALOG_DATABASE = 'roadtrip_web_read';
const POI_TYPE = 'campground';

export const ENABLED_PROVIDER = 'campflare';
export const DISABLED_PROVIDER = 'reserveamerica';
export const ENABLED_PROVIDERS: readonly string[] = [ENABLED_PROVIDER];

export const TAHOE_BOUNDARY = JSON.stringify({
  type: 'Polygon',
  coordinates: [
    [
      [-120.4, 38.7],
      [-119.6, 38.7],
      [-119.6, 39.4],
      [-120.4, 39.4],
      [-120.4, 38.7],
    ],
  ],
});

export const NEAR_POI_ID = 1001n;
export const FAR_POI_ID = 1002n;
export const OUTSIDE_POI_ID = 1003n;
export const DELETED_POI_ID = 1004n;
export const DRIFT_POI_ID = 1005n;
export const UNSERVED_POI_ID = 1006n;
export const UNKNOWN_POI_ID = 9999n;

export const NEAR_CAMPGROUND_ID = 2001n;
export const DRIFT_CAMPGROUND_ID = 2005n;

export const NEAR_NAME = 'Near Campground';
export const FAR_NAME = 'Far Campground';
export const DRIFT_NAME = 'Drifted Campground';
export const NEAR_REGION = 'CA';
export const NEAR_AMENITIES = [{ key: 'toilets', present: true }];
export const NEAR_PRICE = { minimum: 10, maximum: 20, currency: 'USD' };
export const NEAR_CONTACT = { phone: '555-0100' };
export const DRIFT_AMENITIES = [{ key: 'showers', present: true }];
export const UNDECODABLE_PRICE = { minimum: 'cheap' };

export const NEAR_SITE_COUNTS = { tent: 2, rv: 1 };
export const NEAR_SITE_TOTAL = 3;
export const NEAR_MAX_PEOPLE = 8;

interface SeededCampground {
  poiId: bigint;
  campgroundId: bigint;
  name: string;
  wkt: string;
  provider?: string;
  deleted?: boolean;
  region?: string;
  amenities?: unknown;
  price?: unknown;
  contact?: unknown;
}

const SEEDED: readonly SeededCampground[] = [
  {
    poiId: NEAR_POI_ID,
    campgroundId: NEAR_CAMPGROUND_ID,
    name: NEAR_NAME,
    wkt: 'POINT(-120.0 39.05)',
    region: NEAR_REGION,
    amenities: NEAR_AMENITIES,
    price: NEAR_PRICE,
    contact: NEAR_CONTACT,
  },
  { poiId: FAR_POI_ID, campgroundId: 2002n, name: FAR_NAME, wkt: 'POINT(-120.35 38.75)' },
  { poiId: OUTSIDE_POI_ID, campgroundId: 2003n, name: 'Outside Campground', wkt: 'POINT(-118.0 37.0)' },
  {
    poiId: DELETED_POI_ID,
    campgroundId: 2004n,
    name: 'Deleted Campground',
    wkt: 'POINT(-120.1 39.0)',
    deleted: true,
  },
  {
    poiId: DRIFT_POI_ID,
    campgroundId: DRIFT_CAMPGROUND_ID,
    name: DRIFT_NAME,
    wkt: 'POINT(-118.5 37.5)',
    amenities: DRIFT_AMENITIES,
    price: UNDECODABLE_PRICE,
  },
  {
    poiId: UNSERVED_POI_ID,
    campgroundId: 2006n,
    name: 'Unserved Campground',
    wkt: 'POINT(-120.2 39.1)',
    provider: DISABLED_PROVIDER,
  },
];

const NEAR_SITES = [
  { kind: CampsiteKind.TENT, maxPeople: 4 },
  { kind: CampsiteKind.TENT, maxPeople: NEAR_MAX_PEOPLE },
  { kind: CampsiteKind.RV, maxPeople: 6 },
];

async function seedCatalog(db: Db): Promise<void> {
  for (const seed of SEEDED) {
    const provider = seed.provider ?? ENABLED_PROVIDER;
    const deletedAt = seed.deleted ? new Date() : null;
    await db
      .insert(pois)
      .values({ id: seed.poiId, poiType: POI_TYPE, geom: ewkt(seed.wkt), deletedAt });
    await db.insert(campgrounds).values({
      id: seed.campgroundId,
      dataProvider: provider,
      dataProviderRef: `ref-${seed.campgroundId}`,
      name: seed.name,
      location: seed.region === undefined ? {} : { region: seed.region },
      amenities: seed.amenities ?? [],
      price: seed.price ?? {},
      contact: seed.contact ?? {},
    });
    await db
      .insert(poiCampgrounds)
      .values({ poiId: seed.poiId, campgroundId: seed.campgroundId });
  }
  for (const [index, site] of NEAR_SITES.entries()) {
    await db.insert(campsites).values({
      campgroundId: NEAR_CAMPGROUND_ID,
      dataProvider: ENABLED_PROVIDER,
      dataProviderRef: `site-${index}`,
      name: `Site ${index}`,
      kind: site.kind,
      maxPeople: site.maxPeople,
    });
  }
}

export interface CatalogFixture {
  writeDb: Db;
  readDb: Db;
  stop(): Promise<void>;
}

export async function startCatalogFixture(): Promise<CatalogFixture> {
  const held: { postgis?: PostgisSession; shutdown?: () => void } = {};
  let started: () => void = () => {};
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  const session = withPostgis(async (handle) => {
    held.postgis = handle;
    await handle.createDatabase(CATALOG_DATABASE);
    started();
    await new Promise<void>((resolve) => {
      held.shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  const { postgis } = held;
  if (postgis === undefined) throw new Error('PostGIS session ended before it was ready');
  const url = postgis.connectionString(CATALOG_DATABASE);

  const writeDb = createDb(url);
  const readDb = openServerDb(url, true);
  try {
    await migrateDb(writeDb);
    await seedCatalog(writeDb);
  } catch (error) {
    await writeDb.$client.end();
    await readDb.$client.end();
    held.shutdown?.();
    await session;
    throw error;
  }

  return {
    writeDb,
    readDb,
    async stop() {
      await writeDb.$client.end();
      await readDb.$client.end();
      held.shutdown?.();
      await session;
    },
  };
}
