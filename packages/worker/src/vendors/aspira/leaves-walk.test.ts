import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope } from '../../etl/envelope.js';
import { jsonArrayOf } from '../../etl/kotlin-json.js';
import { walkAspiraLeaves } from './leaves-walk.js';

const TEMP_DIR_PREFIX = 'aspira-leaves-walk-';
const HTTP_OK = 200;

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function mapsOf(payloadJson: string): unknown[] {
  counter += 1;
  const file = path.join(dir, `capture-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"test://maps","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return jsonArrayOf(parseEnvelope(file).payload);
}

function leaves(payloadJson: string) {
  return walkAspiraLeaves(mapsOf(payloadJson));
}

const EN_US = '{"cultureName":"en-US","title":"Brooks Memorial"}';

describe('the leaves walk, pass one over the nodes themselves', () => {
  test('a node with a txnLoc and an en title is a leaf', () => {
    expect(leaves(`[{"mapId":1,"transactionLocationId":7,"resourceLocationId":9,"localizedValues":[${EN_US}]}]`)).toEqual([
      {
        name: 'Brooks Memorial',
        transactionLocationId: 7n,
        mapId: 1n,
        resourceLocationId: 9n,
        parentName: null,
      },
    ]);
  });

  test('a node with no txnLoc is navigation and is skipped', () => {
    expect(leaves(`[{"mapId":1,"localizedValues":[${EN_US}]}]`)).toEqual([]);
  });

  test('a node with no en title is skipped, and the culture match is a case-insensitive prefix', () => {
    expect(leaves('[{"mapId":1,"transactionLocationId":7,"localizedValues":[{"cultureName":"fr-CA","title":"Lac"}]}]')).toEqual(
      [],
    );
    expect(
      leaves('[{"mapId":1,"transactionLocationId":7,"localizedValues":[{"cultureName":"EN-ca","title":"Lac"}]}]').map(
        (leaf) => leaf.name,
      ),
    ).toEqual(['Lac']);
    expect(leaves('[{"mapId":1,"transactionLocationId":7,"localizedValues":[{"cultureName":"e","title":"Lac"}]}]')).toEqual([]);
  });

  test('a blank title is not a title, so the walk keeps looking through the array', () => {
    const walked = leaves(
      '[{"mapId":1,"transactionLocationId":7,"localizedValues":' +
        '[{"cultureName":"en-US","title":"   "},{"cultureName":"en-CA","title":"Second"}]}]',
    );
    expect(walked.map((leaf) => leaf.name)).toEqual(['Second']);
  });

  test('a duplicate mapId is only emitted once', () => {
    const walked = leaves(
      `[{"mapId":1,"transactionLocationId":7,"localizedValues":[${EN_US}]},` +
        '{"mapId":1,"transactionLocationId":8,"localizedValues":[{"cultureName":"en-US","title":"Second"}]}]',
    );
    expect(walked.map((leaf) => leaf.transactionLocationId)).toEqual([7n]);
  });

  test('a node whose own title is blank borrows the parent mapLinks title, and records the parent name', () => {
    const walked = leaves(
      '[{"mapId":1,"localizedValues":[{"cultureName":"en-US","title":"Banff"}],' +
        '"mapLinks":[{"childMapId":2,"localizations":[{"cultureName":"en-US","title":"Two Jack"}]}]},' +
        '{"mapId":2,"transactionLocationId":7,"resourceLocationId":9,"parentMap":{"mapId":1}}]',
    );
    expect(walked).toEqual([
      { name: 'Two Jack', transactionLocationId: 7n, mapId: 2n, resourceLocationId: 9n, parentName: 'Banff' },
    ]);
  });

  test('an explicit null parentMap falls through to no parent rather than throwing', () => {
    expect(leaves(`[{"mapId":1,"transactionLocationId":7,"parentMap":null,"localizedValues":[${EN_US}]}]`)).toEqual([
      { name: 'Brooks Memorial', transactionLocationId: 7n, mapId: 1n, resourceLocationId: null, parentName: null },
    ]);
  });

  test('a node without a mapId is refused outright', () => {
    expect(() => leaves('[{"transactionLocationId":7}]')).toThrow('node without mapId');
  });

  test('a non-object entry in the maps array is ignored', () => {
    expect(leaves(`[5,null,"x",[],{"mapId":1,"transactionLocationId":7,"localizedValues":[${EN_US}]}]`)).toHaveLength(1);
  });
});

describe('the leaves walk, pass two over parent mapLinks', () => {
  test('a link that names a child the array does not carry is still a leaf', () => {
    const walked = leaves(
      '[{"mapId":1,"localizedValues":[{"cultureName":"en-US","title":"Jasper"}],"mapLinks":' +
        '[{"childMapId":99,"transactionLocationId":7,"resourceLocationId":9,' +
        '"localizations":[{"cultureName":"en-US","title":"Whistlers"}]}]}]',
    );
    expect(walked).toEqual([
      { name: 'Whistlers', transactionLocationId: 7n, mapId: 99n, resourceLocationId: 9n, parentName: 'Jasper' },
    ]);
  });

  test('a child already emitted by pass one is not emitted again', () => {
    const walked = leaves(
      `[{"mapId":1,"transactionLocationId":7,"localizedValues":[${EN_US}],"mapLinks":` +
        '[{"childMapId":1,"transactionLocationId":8,"localizations":[{"cultureName":"en-US","title":"Again"}]}]}]',
    );
    expect(walked.map((leaf) => leaf.name)).toEqual(['Brooks Memorial']);
  });

  test('a null mapLinks or a null localizations does not crash the walk', () => {
    expect(leaves('[{"mapId":1,"transactionLocationId":null,"mapLinks":null}]')).toEqual([]);
    expect(leaves('[{"mapId":1,"mapLinks":[{"childMapId":2,"transactionLocationId":7,"localizations":null}]}]')).toEqual([]);
  });

  test('pass one refuses a non-array localizedValues where pass two tolerates one', () => {
    expect(() => leaves('[{"mapId":1,"transactionLocationId":7,"localizedValues":{}}]')).toThrow(
      /is not a JsonArray/,
    );
    const walked = leaves(
      '[{"mapId":1,"localizedValues":{},"mapLinks":[{"childMapId":2,"transactionLocationId":7,' +
        '"localizations":[{"cultureName":"en-US","title":"Child"}]}]}]',
    );
    expect(walked.map((leaf) => leaf.parentName)).toEqual([null]);
  });
});

describe('the two numeric readers the walk uses, which Kotlin picks per field', () => {
  test.each([
    ['1e1', 0],
    ['+7', 1],
    [' 7 ', 0],
    ['7', 1],
  ])(
    'transactionLocationId %j is read by the stdlib parser, so it yields %i leaf/leaves',
    (spelling, emitted) => {
      const walked = leaves(
        `[{"mapId":1,"transactionLocationId":${JSON.stringify(spelling)},"localizedValues":[${EN_US}]}]`,
      );
      expect(walked).toHaveLength(emitted);
    },
  );

  test.each([
    ['1e1', 10n],
    [' 7 ', 7n],
    ['7', 7n],
  ])('mapId %j is read by the kotlinx lexer, so it keys as %s', (spelling, expected) => {
    const walked = leaves(
      `[{"mapId":${JSON.stringify(spelling)},"transactionLocationId":7,"localizedValues":[${EN_US}]}]`,
    );
    expect(walked.map((leaf) => leaf.mapId)).toEqual([expected]);
  });

  test('mapId "+7" is refused, because the lexer takes padding and exponents but not a sign', () => {
    expect(() => leaves(`[{"mapId":"+7","transactionLocationId":7,"localizedValues":[${EN_US}]}]`)).toThrow(
      'is not a Long',
    );
  });

  test('resourceLocationId and childMapId follow transactionLocationId, not mapId', () => {
    const lenient = leaves(
      `[{"mapId":1,"transactionLocationId":7,"resourceLocationId":"1e1","localizedValues":[${EN_US}]}]`,
    );
    expect(lenient.map((leaf) => leaf.resourceLocationId)).toEqual([null]);
    const signed = leaves(
      `[{"mapId":1,"transactionLocationId":7,"resourceLocationId":"+9","localizedValues":[${EN_US}]}]`,
    );
    expect(signed.map((leaf) => leaf.resourceLocationId)).toEqual([9n]);

    const exponentChild = leaves(
      '[{"mapId":1,"localizedValues":[{"cultureName":"en-US","title":"Parent"}],"mapLinks":' +
        '[{"childMapId":"1e1","transactionLocationId":7,"localizations":[{"cultureName":"en-US","title":"Child"}]}]}]',
    );
    expect(exponentChild).toEqual([]);
    const signedChild = leaves(
      '[{"mapId":1,"localizedValues":[{"cultureName":"en-US","title":"Parent"}],"mapLinks":' +
        '[{"childMapId":"+9","transactionLocationId":7,"localizations":[{"cultureName":"en-US","title":"Child"}]}]}]',
    );
    expect(signedChild.map((leaf) => leaf.mapId)).toEqual([9n]);
  });
});
