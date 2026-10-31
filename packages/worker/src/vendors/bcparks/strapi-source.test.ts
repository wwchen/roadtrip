import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { BcParksStrapiSource, type BcParksStrapiRow } from './strapi-source.js';

const TEMP_DIR_PREFIX = 'bcparks-strapi-';
const HTTP_OK = 200;

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function envelopeOf(payloadJson: string): Envelope {
  counter += 1;
  const file = path.join(dir, `capture-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"test://strapi","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

function rowsOf(...payloads: string[]): BcParksStrapiRow[] {
  return new BcParksStrapiSource(payloads.map(envelopeOf)).rows();
}

function areaWith(fields: string): string {
  return `{"data":[{"protectedAreaName":"Alice Lake","latitude":"50.4","longitude":"-123.1"${fields}}]}`;
}

function photoUrlOf(photosJson: string): string | null {
  const [row] = rowsOf(areaWith(`,"parkPhotos":${photosJson}`));
  return (row as BcParksStrapiRow).photoUrl;
}

const photo = (url: string, fields = '') => `{"imageUrl":${JSON.stringify(url)}${fields}}`;

describe('the Strapi protected-area rows', () => {
  test('a row carrying a name and a point is parsed with every field', () => {
    expect(
      rowsOf(
        areaWith(
          ',"orcs":"123","url":"https://bcparks.ca/alice","description":"  A lake.  ",' +
            '"parkContact":" 250-555-0100 ","parkPhotos":[{"imageUrl":"https://img/a.jpg"}]',
        ),
      ),
    ).toEqual([
      {
        name: 'Alice Lake',
        lat: 50.4,
        lon: -123.1,
        orcs: 123n,
        url: 'https://bcparks.ca/alice',
        description: 'A lake.',
        phone: '250-555-0100',
        photoUrl: 'https://img/a.jpg',
      },
    ]);
  });

  test('description and parkContact are trimmed where url is not', () => {
    const [row] = rowsOf(areaWith(',"url":" https://bcparks.ca/alice ","description":"  A lake.  "'));
    expect(row?.url).toBe(' https://bcparks.ca/alice ');
    expect(row?.description).toBe('A lake.');
  });

  test('a blank or absent optional field reads as null', () => {
    const [row] = rowsOf(areaWith(',"orcs":"","url":"   ","description":"  ","parkContact":null'));
    expect([row?.orcs, row?.url, row?.description, row?.phone, row?.photoUrl]).toEqual([null, null, null, null, null]);
  });

  test('a row with no name, no latitude or no longitude is skipped', () => {
    expect(rowsOf('{"data":[{"latitude":"50.4","longitude":"-123.1"}]}')).toEqual([]);
    expect(rowsOf('{"data":[{"protectedAreaName":"   ","latitude":"50.4","longitude":"-123.1"}]}')).toEqual([]);
    expect(rowsOf('{"data":[{"protectedAreaName":"Alice Lake","longitude":"-123.1"}]}')).toEqual([]);
    expect(rowsOf('{"data":[{"protectedAreaName":"Alice Lake","latitude":"x","longitude":"-123.1"}]}')).toEqual([]);
  });

  test('a payload with no data key contributes nothing, and pages are concatenated in order', () => {
    expect(rowsOf('{}')).toEqual([]);
    const rows = rowsOf(
      '{"data":[{"protectedAreaName":"First","latitude":"1","longitude":"2"}]}',
      '{"data":[{"protectedAreaName":"Second","latitude":"3","longitude":"4"}]}',
    );
    expect(rows.map((row) => row.name)).toEqual(['First', 'Second']);
  });

  test('orcs and sortOrder are read by the stdlib parser, not the kotlinx lexer', () => {
    const orcsOf = (spelling: string) => rowsOf(areaWith(`,"orcs":${JSON.stringify(spelling)}`))[0]?.orcs;
    expect(orcsOf('1e1')).toBeNull();
    expect(orcsOf(' 10 ')).toBeNull();
    expect(orcsOf('+10')).toBe(10n);
    expect(orcsOf('10')).toBe(10n);

    expect(photoUrlOf(`[${photo('exponent', ',"sortOrder":"1e1"')},${photo('plain', ',"sortOrder":"5"')}]`)).toBe('plain');
    expect(photoUrlOf(`[${photo('signed', ',"sortOrder":"+1"')},${photo('plain', ',"sortOrder":"5"')}]`)).toBe('signed');
  });

  test('points project the parsed rows', () => {
    const source = new BcParksStrapiSource([envelopeOf(areaWith(''))]);
    expect([...source.points()]).toEqual([{ name: 'Alice Lake', latitude: 50.4, longitude: -123.1 }]);
  });
});

describe('the park photo, chosen featured-first and then by sortOrder', () => {
  test('a featured photo beats an unfeatured one whichever order they arrive in', () => {
    expect(photoUrlOf(`[${photo('plain')},${photo('star', ',"isFeatured":"true"')}]`)).toBe('star');
    expect(photoUrlOf(`[${photo('star', ',"isFeatured":"true"')},${photo('plain')}]`)).toBe('star');
  });

  test('a lower sortOrder wins among equally featured photos, whichever order they arrive in', () => {
    expect(photoUrlOf(`[${photo('late', ',"sortOrder":"9"')},${photo('early', ',"sortOrder":"1"')}]`)).toBe('early');
    expect(photoUrlOf(`[${photo('early', ',"sortOrder":"1"')},${photo('late', ',"sortOrder":"9"')}]`)).toBe('early');
  });

  test('featured outranks sortOrder, so a featured late photo still wins', () => {
    expect(
      photoUrlOf(`[${photo('early', ',"sortOrder":"1"')},${photo('late', ',"sortOrder":"9","isFeatured":"true"')}]`),
    ).toBe('late');
  });

  test('a photo with no sortOrder sorts last, and ties keep feed order', () => {
    expect(photoUrlOf(`[${photo('unordered')},${photo('ordered', ',"sortOrder":"5"')}]`)).toBe('ordered');
    expect(photoUrlOf(`[${photo('first')},${photo('second')}]`)).toBe('first');
  });

  test('an inactive photo is dropped even when it would otherwise win', () => {
    expect(photoUrlOf(`[${photo('hidden', ',"isActive":"false","isFeatured":"true"')},${photo('shown')}]`)).toBe('shown');
    expect(photoUrlOf(`[${photo('hidden', ',"isActive":"false"')}]`)).toBeNull();
  });

  test('isActive defaults to true and isFeatured to false, and a non-strict spelling takes the default', () => {
    expect(photoUrlOf(`[${photo('kept')}]`)).toBe('kept');
    expect(photoUrlOf(`[${photo('kept', ',"isActive":"TRUE"')}]`)).toBe('kept');
    expect(photoUrlOf(`[${photo('plain', ',"isFeatured":"yes"')},${photo('star', ',"isFeatured":"true"')}]`)).toBe('star');
  });

  test('a photo with no imageUrl, a non-object entry, and a non-array parkPhotos all yield nothing', () => {
    expect(photoUrlOf('[{"isFeatured":"true"},{"imageUrl":"  "}]')).toBeNull();
    expect(photoUrlOf(`[5,null,${photo('only')}]`)).toBe('only');
    expect(photoUrlOf('{}')).toBeNull();
    expect(photoUrlOf('[]')).toBeNull();
  });
});
