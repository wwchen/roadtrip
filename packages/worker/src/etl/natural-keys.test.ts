import { DataProviderRef } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import type { CampsiteUpsertCandidate } from '../model/campsite-candidate.js';
import type { PlanetFitnessLocationUpsertCandidate } from '../model/planet-fitness-candidate.js';
import type { TeslaSuperchargerUpsertCandidate } from '../model/tesla-candidate.js';
import type { SourceEtl } from './source-etl.js';
import { campgroundBinding, campsiteBinding, planetFitnessBinding, teslaSuperchargerBinding } from './terminal-sinks.js';

const NO_ETL = {} as SourceEtl<never, never>;
const FACILITY_ID = '1';
const SLUG = 'location-a';
const LOCATION_ID = 'gym-1';

describe('the natural key each terminal binding reports to the orchestrator', () => {
  test('a campground is keyed by provider and ref, so one ref under two providers is not a collision', () => {
    const key = campgroundBinding(NO_ETL).naturalKeyOf;
    const recGov = { dataProviderRef: DataProviderRef.recGov(FACILITY_ID) } as CampgroundUpsertCandidate;
    const aspira = { dataProviderRef: DataProviderRef.aspira(1, 2) } as CampgroundUpsertCandidate;
    const bcParks = { dataProviderRef: DataProviderRef.bcParks(1, 2) } as CampgroundUpsertCandidate;
    expect(key(recGov)).toBe(key({ ...recGov }));
    expect(key(aspira)).not.toBe(key(bcParks));
    expect(key(recGov)).not.toBe(key(aspira));
  });

  test('a campsite is keyed by its own data provider ref and not by its parent', () => {
    const key = campsiteBinding(NO_ETL).naturalKeyOf;
    const site = (parent: number) =>
      ({
        dataProviderRef: DataProviderRef.aspiraCampsite('bc', 7),
        parentDataProviderRef: DataProviderRef.aspira(parent, parent),
      }) as CampsiteUpsertCandidate;
    expect(key(site(1))).toBe(key(site(2)));
    expect(key({ ...site(1), dataProviderRef: DataProviderRef.aspiraCampsite('bc', 8) })).not.toBe(key(site(1)));
  });

  test('a Tesla site is keyed by its location slug and a Planet Fitness location by its location id', () => {
    expect(teslaSuperchargerBinding(NO_ETL).naturalKeyOf({ locationSlug: SLUG } as TeslaSuperchargerUpsertCandidate)).toBe(
      SLUG,
    );
    expect(
      planetFitnessBinding(NO_ETL).naturalKeyOf({ locationId: LOCATION_ID } as PlanetFitnessLocationUpsertCandidate),
    ).toBe(LOCATION_ID);
  });
});
