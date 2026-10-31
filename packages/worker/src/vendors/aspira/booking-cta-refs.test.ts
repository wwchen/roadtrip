import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { bookableMapIdsByResourceLocationId, bookingCtaRefForLeaf } from './booking-cta-refs.js';
import { bookableFlagByCategoryId, isBookableResource, nonBookableResourceLocationIds } from './inventory-categories.js';
import type { AspiraLeaf } from './leaf.js';

const TEMP_DIR_PREFIX = 'aspira-booking-cta-';
const HTTP_OK = 200;
const CAMPSITE_CATEGORY = 1;
const PARKING_CATEGORY = 2;
const DICTIONARY = `{"resource_categories":[{"resourceCategoryId":${CAMPSITE_CATEGORY},"showResourceCapacityOnline":true},` +
  `{"resourceCategoryId":${PARKING_CATEGORY},"showResourceCapacityOnline":false}]}`;

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
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"test://inv","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

function dictionaryOf(payloadJson: string): JsonObject | null {
  const payload = envelopeOf(payloadJson).payload;
  return isJsonObject(payload) ? payload : null;
}

function leaf(resourceLocationId: bigint | null, mapId = 500n): AspiraLeaf {
  return { name: 'Leaf', transactionLocationId: 7n, mapId, resourceLocationId, parentName: null };
}

describe('the non-bookable classification, driven by the tenant dictionary', () => {
  test('a resource location whose every resource is a non-bookable category is dropped', () => {
    const inventory = [envelopeOf(`{"r1":{"resourceLocationId":10,"resourceCategoryId":${PARKING_CATEGORY}}}`)];
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf(DICTIONARY))).toEqual(new Set([10n]));
  });

  test('one bookable resource keeps the whole resource location', () => {
    const inventory = [
      envelopeOf(
        `{"r1":{"resourceLocationId":10,"resourceCategoryId":${PARKING_CATEGORY}},` +
          `"r2":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY}}}`,
      ),
    ];
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf(DICTIONARY))).toEqual(new Set());
  });

  test('an unmapped category fails open, so a new category never drops a real campground', () => {
    const inventory = [envelopeOf('{"r1":{"resourceLocationId":10,"resourceCategoryId":77}}')];
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf(DICTIONARY))).toEqual(new Set());
    const noCategory = [envelopeOf('{"r1":{"resourceLocationId":10}}')];
    expect(nonBookableResourceLocationIds(noCategory, dictionaryOf(DICTIONARY))).toEqual(new Set());
  });

  test('a tenant whose dictionary says nothing drops nothing at all', () => {
    const inventory = [envelopeOf(`{"r1":{"resourceLocationId":10,"resourceCategoryId":${PARKING_CATEGORY}}}`)];
    expect(nonBookableResourceLocationIds(inventory, null)).toEqual(new Set());
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf('{}'))).toEqual(new Set());
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf('{"resource_categories":[]}'))).toEqual(new Set());
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf('{"resource_categories":{}}'))).toEqual(new Set());
  });

  test('a missing capacity flag reads as bookable', () => {
    const flags = bookableFlagByCategoryId(dictionaryOf('{"resource_categories":[{"resourceCategoryId":3}]}'));
    expect(flags).toEqual(new Map([[3, true]]));
    expect(isBookableResource({ resourceCategoryId: 3 }, flags)).toBe(true);
  });

  test('an empty dictionary makes every resource bookable without consulting its category', () => {
    expect(isBookableResource({ resourceCategoryId: PARKING_CATEGORY }, new Map())).toBe(true);
  });

  test('a non-object inventory entry or payload is skipped', () => {
    const inventory = [envelopeOf('[1,2]'), envelopeOf(`{"r1":5,"r2":null,"r3":{"resourceLocationId":10,"resourceCategoryId":${PARKING_CATEGORY}}}`)];
    expect(nonBookableResourceLocationIds(inventory, dictionaryOf(DICTIONARY))).toEqual(new Set([10n]));
  });

  test("the classifier reads resourceLocationId with Kotlin's stdlib parse, which takes a sign and not padding", () => {
    const padded = [envelopeOf(`{"r1":{"resourceLocationId":" 10 ","resourceCategoryId":${PARKING_CATEGORY}}}`)];
    expect(nonBookableResourceLocationIds(padded, dictionaryOf(DICTIONARY))).toEqual(new Set());
    const signed = [envelopeOf(`{"r1":{"resourceLocationId":"+10","resourceCategoryId":${PARKING_CATEGORY}}}`)];
    expect(nonBookableResourceLocationIds(signed, dictionaryOf(DICTIONARY))).toEqual(new Set([10n]));
  });
});

describe('the booking CTA index, which reads resourceLocationId through the lenient lexer instead', () => {
  test('a resource location on one map deep-links to that child map', () => {
    const inventory = [
      envelopeOf(`{"r1":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[42]}}`),
    ];
    const index = bookableMapIdsByResourceLocationId(inventory, dictionaryOf(DICTIONARY));
    expect(index).toEqual(new Map([[10n, new Set([42n])]]));
    expect(bookingCtaRefForLeaf(leaf(10n), index)).toEqual({ mapId: 42n, resourceLocationId: 10n });
  });

  test('a resource location spread over several maps keeps the container map', () => {
    const inventory = [
      envelopeOf(
        `{"r1":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[42]},` +
          `"r2":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[43]}}`,
      ),
    ];
    const index = bookableMapIdsByResourceLocationId(inventory, dictionaryOf(DICTIONARY));
    expect(index.get(10n)).toEqual(new Set([42n, 43n]));
    expect(bookingCtaRefForLeaf(leaf(10n, 500n), index)).toEqual({ mapId: 500n, resourceLocationId: 10n });
  });

  test('the same map listed twice is still one map, so the child link survives', () => {
    const inventory = [
      envelopeOf(
        `{"r1":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[42,42]},` +
          `"r2":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[42]}}`,
      ),
    ];
    const index = bookableMapIdsByResourceLocationId(inventory, dictionaryOf(DICTIONARY));
    expect(bookingCtaRefForLeaf(leaf(10n), index)).toEqual({ mapId: 42n, resourceLocationId: 10n });
  });

  test('a non-bookable resource contributes no map, so its leaf gets no CTA', () => {
    const inventory = [
      envelopeOf(`{"r1":{"resourceLocationId":10,"resourceCategoryId":${PARKING_CATEGORY},"mapIds":[42]}}`),
    ];
    const index = bookableMapIdsByResourceLocationId(inventory, dictionaryOf(DICTIONARY));
    expect(index).toEqual(new Map());
    expect(bookingCtaRefForLeaf(leaf(10n), index)).toBeNull();
  });

  test('a resource listed on no map is never keyed, so a key always means real inventory', () => {
    const inventory = [
      envelopeOf(
        `{"r1":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[]},` +
          `"r2":{"resourceLocationId":11,"resourceCategoryId":${CAMPSITE_CATEGORY}}}`,
      ),
    ];
    expect(bookableMapIdsByResourceLocationId(inventory, dictionaryOf(DICTIONARY))).toEqual(new Map());
  });

  test('a leaf with no resourceLocationId has no CTA', () => {
    expect(bookingCtaRefForLeaf(leaf(null), new Map([[10n, new Set([42n])]]))).toBeNull();
  });

  test('the two readers disagree in both directions, which is the Kotlin they are ported from', () => {
    const resource = (id: string, category: number) =>
      `{"r1":{"resourceLocationId":${JSON.stringify(id)},"resourceCategoryId":${category},"mapIds":[42]}}`;
    const dictionary = () => dictionaryOf(DICTIONARY);

    const exponentBookable = [envelopeOf(resource('1e1', CAMPSITE_CATEGORY))];
    expect(bookableMapIdsByResourceLocationId(exponentBookable, dictionary())).toEqual(new Map([[10n, new Set([42n])]]));
    const exponentParking = [envelopeOf(resource('1e1', PARKING_CATEGORY))];
    expect(nonBookableResourceLocationIds(exponentParking, dictionary())).toEqual(new Set());

    const signedBookable = [envelopeOf(resource('+10', CAMPSITE_CATEGORY))];
    expect(bookableMapIdsByResourceLocationId(signedBookable, dictionary())).toEqual(new Map());
    const signedParking = [envelopeOf(resource('+10', PARKING_CATEGORY))];
    expect(nonBookableResourceLocationIds(signedParking, dictionary())).toEqual(new Set([10n]));
  });

  test('a mapIds element that is not a number is dropped, and a non-array mapIds yields nothing', () => {
    const mixed = [
      envelopeOf(`{"r1":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":[42,"x",null]}}`),
    ];
    expect(bookableMapIdsByResourceLocationId(mixed, dictionaryOf(DICTIONARY)).get(10n)).toEqual(new Set([42n]));
    const notAnArray = [
      envelopeOf(`{"r1":{"resourceLocationId":10,"resourceCategoryId":${CAMPSITE_CATEGORY},"mapIds":{}}}`),
    ];
    expect(bookableMapIdsByResourceLocationId(notAnArray, dictionaryOf(DICTIONARY))).toEqual(new Map());
  });
});
