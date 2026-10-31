import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import type { RunLog } from '../../etl/orchestrator.js';
import { buildGeometryIndex } from './geometry-index.js';
import type { GeometryPoint } from './geometry-point.js';
import { normalize } from './normalize.js';
import { UsCampgroundsCsvSource, csvSplit } from './us-campgrounds-csv.js';

const TEMP_DIR_PREFIX = 'aspira-uscampgrounds-';
const HTTP_OK = 200;
const SLUG = 'uscampgrounds';
const ETL_SLUG = 'uscampgrounds-csv-source-test';
const SILENT: RunLog = { info: () => undefined, warn: () => undefined };

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function row(longitude: string, latitude: string, name: string, state: string): string {
  return [longitude, latitude, '', '', name, '', '', '', '', '', '', '', state].join(',');
}

const CSV = [
  row('-101.732', '43.177', 'Brooks Memorial', 'SD'),
  row('-120.667', '46.039', 'Brooks Memorial', 'WA'),
  row('-122.545', '47.548', 'Manchester', 'WA'),
].join('\n');

function envelopeOf(payloadJson: string): Envelope {
  counter += 1;
  const file = path.join(dir, `capture-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"test://us","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

function indexOf(stateFilter: string | null, csv: string = CSV): Map<string, GeometryPoint> {
  return buildGeometryIndex(
    [[SLUG, new UsCampgroundsCsvSource([envelopeOf(JSON.stringify(csv))], stateFilter)]],
    SILENT,
    ETL_SLUG,
  );
}

describe('the nationwide CSV, where a state filter is what stops a name collision crossing state lines', () => {
  test('a state filter keeps the matching state coordinates even though another state is listed first', () => {
    expect(indexOf('WA').get(normalize('Brooks Memorial'))).toEqual({
      latitude: 46.039,
      longitude: -120.667,
      source: SLUG,
    });
  });

  test('a state filter drops other states entirely, so no fuzzy match can reach them', () => {
    const byName = indexOf('OR');
    expect(byName.get(normalize('Brooks Memorial'))).toBeUndefined();
    expect(byName.get(normalize('Manchester'))).toBeUndefined();
  });

  test('no state filter indexes every state, the first row winning', () => {
    const byName = indexOf(null);
    expect(byName.get(normalize('Brooks Memorial'))).toEqual({ latitude: 43.177, longitude: -101.732, source: SLUG });
    expect(byName.get(normalize('Manchester'))).toEqual({ latitude: 47.548, longitude: -122.545, source: SLUG });
  });

  test('the filter and the column are compared case-insensitively and trimmed on both sides', () => {
    expect(indexOf(' wa ').get(normalize('Manchester'))).toBeDefined();
    expect(indexOf('WA', row('-122.545', '47.548', 'Manchester', ' wa ')).get(normalize('Manchester'))).toBeDefined();
  });

  test('a blank filter indexes every state rather than matching nothing', () => {
    expect(indexOf('   ').get(normalize('Brooks Memorial'))).toEqual({
      latitude: 43.177,
      longitude: -101.732,
      source: SLUG,
    });
  });

  test('a short row, a blank line and an unparseable coordinate are skipped, not fatal', () => {
    const csv = ['', 'a,b,c', row('x', '43.177', 'Bad Longitude', 'WA'), row('-101.732', 'y', 'Bad Latitude', 'WA'), '   ', CSV].join(
      '\n',
    );
    const byName = indexOf('WA', csv);
    expect([...byName.keys()]).toEqual([normalize('Brooks Memorial'), normalize('Manchester')]);
  });

  test('a row whose name column is blank is skipped', () => {
    expect(indexOf('WA', row('-120.667', '46.039', '   ', 'WA'))).toEqual(new Map());
  });
});

describe('csvSplit, which keeps commas inside quotes', () => {
  test.each([
    ['a,b,c', ['a', 'b', 'c']],
    ['', ['']],
    ['a,,c', ['a', '', 'c']],
    ['"a,b",c', ['a,b', 'c']],
    ['a,"b""c",d', ['a', 'bc', 'd']],
    ['a,b,', ['a', 'b', '']],
  ])('%j', (line, expected) => {
    expect(csvSplit(line)).toEqual(expected);
  });

  test('a quoted field keeps a comma that would otherwise shift every later column', () => {
    const quoted = ['-120.667', '46.039', '', '', '"Fort Ebey, Whidbey"', '', '', '', '', '', '', '', 'WA'].join(',');
    expect(indexOf('WA', quoted).get(normalize('Fort Ebey Whidbey'))).toBeDefined();
  });
});
