import { describe, expect, test } from 'vitest';
import type { TransformResult } from '../../etl/source-etl.js';
import type { PlanetFitnessLocationUpsertCandidate } from '../../model/planet-fitness-candidate.js';
import { PlanetFitnessEtl } from './planet-fitness.js';

const LATITUDE = 43.7;
const LONGITUDE = -79.4;
const NO_COUNTRY = 'no country in the OSM address tags';

function resultOf(tags: Record<string, string> | null): TransformResult<PlanetFitnessLocationUpsertCandidate> {
  const [only] = new PlanetFitnessEtl().transform({
    elements: [{ type: 'node', id: 7, lat: LATITUDE, lon: LONGITUDE, center: null, tags }],
  });
  if (only === undefined) throw new Error('expected one result');
  return only;
}

function recordOf(tags: Record<string, string> | null): PlanetFitnessLocationUpsertCandidate {
  const result = resultOf(tags);
  if (!result.ok) throw new Error(result.errors.join());
  return result.record;
}

function addressOf(record: PlanetFitnessLocationUpsertCandidate): Record<string, unknown> {
  return JSON.parse(record.address as string) as Record<string, unknown>;
}

describe('the country of a gym', () => {
  test('a listed addr:country is taken as written', () => {
    expect(recordOf({ 'addr:country': ' CA ', 'addr:state': 'NY' }).country).toBe('CA');
  });

  test('a Canadian postcode, an addr:province or a province code in addr:state places a gym in Canada', () => {
    expect(recordOf({ 'addr:postcode': 'L7N 4X7' }).country).toBe('CA');
    expect(recordOf({ 'addr:postcode': 'v3t2x3' }).country).toBe('CA');
    expect(recordOf({ 'addr:province': 'Ontario' }).country).toBe('CA');
    expect(recordOf({ 'addr:state': 'on' }).country).toBe('CA');
  });

  test('a ZIP code or a state places a gym in the United States', () => {
    expect(recordOf({ 'addr:postcode': '97071' }).country).toBe('US');
    expect(recordOf({ 'addr:postcode': '97071-1234' }).country).toBe('US');
    expect(recordOf({ 'addr:state': 'Washington' }).country).toBe('US');
  });

  test('a gym with nothing to place it is kept with no country and a warning, not assumed American', () => {
    expect(resultOf({ name: 'Planet Fitness' })).toEqual(
      expect.objectContaining({ ok: true, warnings: [NO_COUNTRY], record: expect.objectContaining({ country: null }) }),
    );
    expect(resultOf(null)).toEqual(expect.objectContaining({ ok: true, warnings: [NO_COUNTRY] }));
    expect(resultOf({ 'addr:postcode': '97071' })).toEqual(expect.objectContaining({ ok: true, warnings: [] }));
  });

  test('the address carries the same country, and a province stands in for a missing state', () => {
    const record = recordOf({
      'addr:housenumber': '1025',
      'addr:street': 'Marine Drive',
      'addr:city': 'North Vancouver',
      'addr:province': 'BC',
    });
    expect(record.region).toBe('BC');
    expect(addressOf(record)).toEqual({
      street: '1025 Marine Drive',
      city: 'North Vancouver',
      state: 'BC',
      country: 'CA',
    });
  });
});
