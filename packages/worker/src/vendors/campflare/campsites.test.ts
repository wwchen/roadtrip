import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { CampsiteKind } from '@roadtrip/domain';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import { CampflareCampsitesEtl } from './campsites.js';

const TEMP_DIR_PREFIX = 'campflare-campsites-';
const HTTP_OK = 200;
const INPUT_SLUG = 'campflare-campsites-export';

let dir = '';

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function captured(rowsJson: string): InputBundle {
  const file = path.join(dir, 'page.json');
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"u","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${rowsJson}}`,
  );
  return new InputBundle(new Map([[INPUT_SLUG, [parseEnvelope(file)]]]));
}

function transformed(rowJson: string): TransformResult<CampsiteUpsertCandidate>[] {
  const etl = new CampflareCampsitesEtl();
  const results: TransformResult<CampsiteUpsertCandidate>[] = [];
  for (const parsed of etl.parse(captured(`[${rowJson}]`))) {
    if (!parsed.ok) throw new Error(parsed.errors.join());
    results.push(...etl.transform(parsed.dto));
  }
  return results;
}

function recordOf(rowJson: string): CampsiteUpsertCandidate {
  const [only, ...rest] = transformed(rowJson);
  if (only === undefined || !only.ok || rest.length > 0) throw new Error('expected exactly one ok record');
  return only.record;
}

const BASE = '"id":"s1","campground_id":"c1","name":"Site 1"';

describe('campsite price and schedule are raw vendor json', () => {
  test('every upstream key survives, including the ones a typed campground shape would drop', () => {
    const record = recordOf(
      `{${BASE},"price":{"per_night":26.0,"currency_code":"USD","extra":[1,2]},` +
        `"schedule":{"check_in_time":"14:00","uniform":true}}`,
    );
    expect(JSON.parse(record.price as string)).toEqual({ per_night: 26, currency_code: 'USD', extra: [1, 2] });
    expect(JSON.parse(record.schedule as string)).toEqual({ check_in_time: '14:00', uniform: true });
  });

  test('an object with none of the typed keys, or an empty one, is still stored as it came', () => {
    const record = recordOf(`{${BASE},"price":{"uniform":true,"per_night":null},"schedule":{}}`);
    expect(JSON.parse(record.price as string)).toEqual({ uniform: true, per_night: null });
    expect(JSON.parse(record.schedule as string)).toEqual({});
  });

  test('a json null or a non-object is no payload at all', () => {
    expect(recordOf(`{${BASE},"price":null,"schedule":[1]}`)).toMatchObject({ price: null, schedule: null });
  });
});

describe('integer columns', () => {
  test('a whole number written with a fraction is the integer it names, as is a bare integer', () => {
    const record = recordOf(
      `{${BASE},"max_people":8,"max_cars":2.0,"driveway_length":40.0,"max_rv_length":25,"max_trailer_length":25.0}`,
    );
    expect(record).toMatchObject({
      maxPeople: 8,
      maxCars: 2,
      drivewayLength: 40,
      maxRvLength: 25,
      maxTrailerLength: 25,
    });
    expect(transformed(`{${BASE},"kind":"rv","driveway_length":40.0,"max_rv_length":-0.0}`)[0]).toMatchObject({
      ok: true,
      warnings: [],
      record: { drivewayLength: 40, maxRvLength: 0 },
    });
  });

  test('a true fraction, a number past the int range or a non-number is null and named in a warning', () => {
    const [result] = transformed(
      `{${BASE},"kind":"rv","max_people":"many","max_cars":null,"driveway_length":45.5,"max_rv_length":3000000000.0}`,
    );
    expect(result).toMatchObject({
      ok: true,
      record: { maxPeople: null, maxCars: null, drivewayLength: null, maxRvLength: null },
      warnings: [
        'Campflare max_people many is not a whole number',
        'Campflare driveway_length 45.5 is not a whole number',
        'Campflare max_rv_length 3000000000.0 is not a whole number',
      ],
    });
  });

  test('a negative count or length is null and named in a warning, since no column holds one', () => {
    const [result] = transformed(`{${BASE},"kind":"rv","max_cars":0,"driveway_length":-2.0,"max_rv_length":-3}`);
    expect(result).toMatchObject({
      ok: true,
      record: { maxCars: 0, drivewayLength: null, maxRvLength: null },
      warnings: ['Campflare driveway_length -2.0 is negative', 'Campflare max_rv_length -3 is negative'],
    });
  });

  test('a quoted whole number reads as the integer it names', () => {
    expect(recordOf(`{${BASE},"driveway_length":"40.0"}`).drivewayLength).toBe(40);
  });
});

describe('kind', () => {
  test('an unlisted Campflare kind is kept as OTHER with the kind named in a warning', () => {
    expect(transformed(`{${BASE},"kind":"houseboat"}`)[0]).toMatchObject({
      ok: true,
      record: { kind: CampsiteKind.OTHER, kindListed: 'houseboat' },
      warnings: ["unlisted Campflare kind 'houseboat'"],
    });
  });
});

describe('equipment', () => {
  test('takes bare names and name objects, trims them, and drops everything else', () => {
    const record = recordOf(
      `{${BASE},"equipment":["Tent"," Trailer ",{"name":" RV "},{"name":null},{"label":"x"},[1],null,"  ",5]}`,
    );
    expect(record.equipment).toEqual(['Tent', 'Trailer', 'RV', '5']);
  });

  test('a non-array equipment is empty', () => {
    expect(recordOf(`{${BASE},"equipment":"Tent"}`).equipment).toEqual([]);
  });
});

describe('the rest of the record', () => {
  test('kind_listed falls back to the raw kind, the kind is mapped, and the description is stripped', () => {
    const record = recordOf(`{${BASE},"kind":"tent-only","description":"<p>Quiet  <b>loop</b></p>"}`);
    expect(record).toMatchObject({ kind: CampsiteKind.TENT, kindListed: 'tent-only', description: 'Quiet loop' });
  });

  test('a description that is only markup is null', () => {
    expect(recordOf(`{${BASE},"description":"<br/> <p></p>"}`).description).toBeNull();
  });

  test('a rec.gov campsite url yields the booking alias', () => {
    const record = recordOf(`{${BASE},"reservation_url":"https://www.recreation.gov/camping/campsites/555"}`);
    expect(record.bookingAliases).toEqual([{ provider: 'recgov', ref: '555' }]);
  });

  test('a missing id, campground or name is bad and carries the id when there is one', () => {
    expect(transformed('{"campground_id":"c1","name":"n"}')).toEqual([{ ok: false, errors: ['missing id'] }]);
    expect(transformed('{"id":"s1","name":"  "}')).toEqual([
      { ok: false, sourceId: 's1', errors: ['missing campground_id', 'missing name'] },
    ]);
  });

  test('the source payload is the row verbatim', () => {
    const record = recordOf(`{${BASE},"driveway_length":25.0,"unmapped":{"a":[1,2.50]}}`);
    expect(record.sourcePayload).toBe(`{"id":"s1","campground_id":"c1","name":"Site 1","driveway_length":25.0,"unmapped":{"a":[1,2.50]}}`);
  });
});
