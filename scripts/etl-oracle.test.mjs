import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonical, compareCallCase, compareEtlCase, floorProblems, strip, summarise } from './etl-oracle/compare.mjs';
import { etlEntryFor, instanceFingerprint, registryWithOverride, requireEtlEntry, seamProblems } from './etl-oracle/registry-seam.mjs';

const okTs = (results) => ({ tsParse: 'ok', tsParseBad: [], tsTransformRan: true, tsTransform: 'ok', tsResults: results });
const okKotlin = (results) => ({ parse: 'ok', parseBad: [], transform: 'ok', results });
const record = (fields) => ({ ok: true, record: fields });

test('an absent field, a null and an empty list are the same thing to the comparison', () => {
  assert.deepEqual(strip({ a: null, b: undefined, c: [], d: {}, e: [null], f: 0, g: false, h: '' }), { f: 0, g: false, h: '' });
  assert.deepEqual(compareEtlCase(okTs([record({ name: 'x', links: [] })]), okKotlin([record({ name: 'x', links: null, extra: null })])), []);
});

test('a value that differs, anywhere in the record, is reported with both sides', () => {
  const problems = compareEtlCase(okTs([record({ nested: { count: 1 } })]), okKotlin([record({ nested: { count: 2 } })]));
  assert.equal(problems.length, 1);
  assert.match(problems[0], /results differ/);
  assert.match(problems[0], /"count":1/);
  assert.match(problems[0], /"count":2/);
});

test('0 and false and the empty string are values, not absences', () => {
  assert.equal(compareEtlCase(okTs([record({ n: 0 })]), okKotlin([record({})])).length, 1);
  assert.equal(compareEtlCase(okTs([record({ n: false })]), okKotlin([record({})])).length, 1);
  assert.equal(compareEtlCase(okTs([record({ n: '' })]), okKotlin([record({})])).length, 1);
});

test('the order of results, and a missing or extra result, are differences', () => {
  const a = record({ name: 'a' });
  const b = record({ name: 'b' });
  assert.equal(compareEtlCase(okTs([a, b]), okKotlin([b, a])).length, 1);
  assert.equal(compareEtlCase(okTs([a]), okKotlin([a, b])).length, 1);
  assert.equal(compareEtlCase(okTs([a, b]), okKotlin([a])).length, 1);
});

test('a null inside a list is dropped, so [null, 1] equals [1], which the usage text names as a blind spot', () => {
  assert.deepEqual(strip({ a: [null, 1] }), { a: [1] });
  assert.deepEqual(compareEtlCase(okTs([record({ a: [null, 1] })]), okKotlin([record({ a: [1] })])), []);
});

test('the fetched_at each side saw is compared', () => {
  const withFetchedAt = (current, fetchedAt) => ({ ...current, fetchedAt });
  assert.deepEqual(compareEtlCase(withFetchedAt(okTs([]), ['a', 'b']), withFetchedAt(okKotlin([]), ['a', 'b'])), []);
  assert.equal(compareEtlCase(withFetchedAt(okTs([]), ['a', 'b']), withFetchedAt(okKotlin([]), ['a', 'c'])).length, 1);
  assert.equal(compareEtlCase(withFetchedAt(okTs([]), ['a']), withFetchedAt(okKotlin([]), ['a', 'a'])).length, 1);
  assert.equal(compareEtlCase(withFetchedAt(okTs([]), ['a']), okKotlin([])).length, 1);
});

test('a bad result compares by its source id and errors', () => {
  const bad = (sourceId, errors) => ({ ok: false, sourceId, errors });
  assert.deepEqual(compareEtlCase(okTs([bad('1', ['x'])]), okKotlin([bad('1', ['x'])])), []);
  assert.equal(compareEtlCase(okTs([bad('1', ['x'])]), okKotlin([bad('2', ['x'])])).length, 1);
  assert.equal(compareEtlCase(okTs([bad('1', ['x'])]), okKotlin([bad('1', ['y'])])).length, 1);
});

test('a throw on one side only is a difference, and a throw on both sides is agreement', () => {
  const tsThrows = { ...okTs([]), tsParse: 'throws' };
  assert.equal(compareEtlCase(tsThrows, okKotlin([])).length, 1);
  assert.deepEqual(compareEtlCase(tsThrows, { parse: 'throws', parseBad: [], transform: 'skipped', results: [] }), []);
  const tsTransformThrows = { ...okTs([]), tsTransform: 'throws' };
  assert.equal(compareEtlCase(tsTransformThrows, okKotlin([])).length, 1);
  assert.deepEqual(compareEtlCase(tsTransformThrows, { ...okKotlin([]), transform: 'throws' }), []);
});

test('results are not compared when the unit test never ran the transform', () => {
  const parseOnly = { ...okTs([]), tsTransformRan: false };
  assert.deepEqual(compareEtlCase(parseOnly, okKotlin([record({ name: 'x' })])), []);
  assert.equal(compareEtlCase(parseOnly, { ...okKotlin([]), parse: 'throws' }).length, 1);
});

test('parse-level errors are compared', () => {
  const withBad = { ...okTs([]), tsParseBad: [['no rows']] };
  assert.deepEqual(compareEtlCase(withBad, { ...okKotlin([]), parseBad: [['no rows']] }), []);
  assert.equal(compareEtlCase(withBad, okKotlin([])).length, 1);
});

test('a call case compares its value and whether it threw', () => {
  assert.deepEqual(compareCallCase({ threw: false, value: 'a' }, { threw: false, value: 'a' }), []);
  assert.equal(compareCallCase({ threw: false, value: 'a' }, { threw: false, value: 'b' }).length, 1);
  assert.equal(compareCallCase({ threw: false, value: null }, { threw: false, value: 'b' }).length, 1);
  assert.equal(compareCallCase({ threw: true, value: null }, { threw: false, value: null }).length, 1);
  assert.deepEqual(compareCallCase({ threw: true, value: null }, { threw: true, value: null }), []);
});

test('summarise refuses a different number of answers than cases, and lists each failing case by index', () => {
  assert.throws(() => summarise([{}, {}], [{}], () => []), /2 cases .* 1 answers/);
  const summary = summarise([{ n: 0 }, { n: 1 }, { n: 2 }], [{}, {}, {}], (current) => (current.n === 1 ? ['bad'] : []));
  assert.equal(summary.compared, 3);
  assert.deepEqual(summary.failures.map((failure) => failure.index), [1]);
});

test('canonical output is plain JSON, so number spellings and key order do not matter', () => {
  assert.deepEqual(canonical({ b: 1, a: [2.0, { c: null }] }), { a: [2], b: 1 });
});

const adapters = (floors) => ({ one: { minimumCases: 2, minimumTransformed: 1, ...floors } });
const recordedFor = (adapter, count, transformed) =>
  Array.from({ length: count }, (_, index) => ({ adapter, tsParse: 'ok', tsTransformRan: index < transformed }));

test('an adapter with no recorded case fails the floor, and so does one below its floor', () => {
  assert.equal(floorProblems([], adapters({}), {}, {}).problems.length, 2);
  assert.equal(floorProblems(recordedFor('one', 1, 1), adapters({}), {}, {}).problems.length, 1);
  assert.deepEqual(floorProblems(recordedFor('one', 2, 1), adapters({}), {}, {}).problems, []);
});

test('cases recorded for another adapter, or that never ran the transform, do not count', () => {
  assert.equal(floorProblems(recordedFor('two', 5, 5), adapters({}), {}, {}).problems.length, 2);
  assert.equal(floorProblems(recordedFor('one', 3, 0), adapters({}), {}, {}).problems.length, 1);
  const parseFailed = [{ adapter: 'one', tsParse: 'throws', tsTransformRan: true }, ...recordedFor('one', 1, 0)];
  assert.equal(floorProblems(parseFailed, adapters({ minimumCases: 1 }), {}, {}).problems.length, 1);
});

test('a floor that is missing, zero or not an integer is itself a problem', () => {
  for (const bad of [{ minimumCases: undefined }, { minimumCases: 0 }, { minimumTransformed: 1.5 }, { minimumTransformed: '1' }]) {
    assert.match(floorProblems(recordedFor('one', 9, 9), adapters(bad), {}, {}).problems[0], /must set minimumCases and minimumTransformed/);
  }
});

test('a call with too few committed cases, or no floor, fails', () => {
  const calls = { path: { minimumCases: 3 } };
  assert.equal(floorProblems([], {}, calls, { path: 2 }).problems.length, 1);
  assert.equal(floorProblems([], {}, calls, {}).problems.length, 1);
  assert.deepEqual(floorProblems([], {}, calls, { path: 3 }).problems, []);
  assert.equal(floorProblems([], {}, { path: {} }, { path: 9 }).problems.length, 1);
});

test('the counts come back per adapter', () => {
  const { counts } = floorProblems([...recordedFor('one', 3, 2)], adapters({}), {}, {});
  assert.deepEqual(counts, { one: { cases: 3, transformed: 2 } });
});

const seamRegistry = () => ({
  dataSources: [],
  poiData: [
    {
      name: 'Washington State Parks',
      enabled: true,
      category: 'campground',
      subcategory: 'state',
      agency: { kind: 'constant', value: 'WA State Parks' },
      etls: [
        {
          slug: 'aspira-wa-campgrounds',
          adapter: 'AspiraCampgroundsEtl',
          inputs: ['uscampgrounds', 'apca-places'],
          args: { tenant: 'wa', host: 'washington.goingtocamp.com' },
          geometry: {
            sources: [
              { input: 'uscampgrounds', format: 'uscampgrounds_csv', state: 'WA' },
              { input: 'apca-places', format: 'arcgis_centroids' },
            ],
            match: { fuzzyThreshold: 0.5, parentFallback: true },
          },
        },
      ],
    },
    {
      name: 'Rec.gov Campgrounds',
      enabled: true,
      category: 'campground',
      subcategory: 'federal',
      agency: { kind: 'derived', field: 'ORGANIZATION[0].OrgName' },
      etls: [{ slug: 'recgov-campgrounds', adapter: 'RecGovCampgroundsEtl', inputs: [], args: {} }],
    },
  ],
  campsiteData: [
    { name: 'Rec.gov Campsites', enabled: true, etls: [{ slug: 'recgov-campsites', adapter: 'RecGovCampsitesEtl', inputs: [], args: {} }] },
  ],
  bookingProviders: [],
});

class RecGovCampgroundsEtl {
  constructor(slug) {
    this.etlSlug = slug;
  }
}

class AspiraCampgroundsEtl {
  constructor(slug, tenant, geometry) {
    this.etlSlug = slug;
    this.aspiraTenant = tenant;
    this.geometry = geometry;
  }
}

const seamRegistryAdapters = {
  RecGovCampgroundsEtl: (entry) => ({ etl: new RecGovCampgroundsEtl(entry.slug) }),
  AspiraCampgroundsEtl: (entry) => ({ etl: new AspiraCampgroundsEtl(entry.slug, entry.args.tenant, entry.geometry) }),
};

const seamCase = (fields) => ({
  adapter: 'recgov-campgrounds',
  slug: 'recgov-campgrounds',
  registry: { subcategory: null, agency: null, args: {} },
  instance: instanceFingerprint(new RecGovCampgroundsEtl('recgov-campgrounds')),
  ...fields,
});
const seamAdapters = { 'recgov-campgrounds': { export: 'RecGovCampgroundsEtl' }, 'aspira-wa-campgrounds': { export: 'AspiraCampgroundsEtl' } };
const problemsFor = (cases, registry = seamRegistry(), adapters = seamAdapters) =>
  seamProblems(cases, registry, adapters, seamRegistryAdapters);

test('the etl entry is found by slug in either section, and nowhere else', () => {
  const registry = seamRegistry();
  assert.equal(etlEntryFor(registry, 'aspira-wa-campgrounds').adapter, 'AspiraCampgroundsEtl');
  assert.equal(etlEntryFor(registry, 'recgov-campsites').adapter, 'RecGovCampsitesEtl');
  assert.equal(etlEntryFor(registry, 'Rec.gov Campgrounds'), undefined);
  assert.throws(() => requireEtlEntry(registry, 'nope'), /no etl with slug 'nope'/);
});

test('the override replaces the shipped subcategory and agency on the owning row only', () => {
  const registry = seamRegistry();
  const next = registryWithOverride(registry, 'recgov-campgrounds', { subcategory: 'overridden', agency: { kind: 'constant', value: 'Fixed Agency' } });
  const [washington, recgov] = next.poiData;
  assert.deepEqual(recgov.agency, { kind: 'constant', value: 'Fixed Agency' });
  assert.equal(recgov.subcategory, 'overridden');
  assert.equal(washington, registry.poiData[0]);
  assert.equal(registry.poiData[1].subcategory, 'federal', 'the registry handed in is not mutated');
  assert.equal(next.poiDataByName('Rec.gov Campgrounds'), recgov);
});

test('a null override clears what the shipped row declares, because the test ctx had nothing there', () => {
  const next = registryWithOverride(seamRegistry(), 'recgov-campgrounds', { subcategory: null, agency: null });
  assert.equal(next.poiData[1].subcategory, undefined);
  assert.equal(next.poiData[1].agency, undefined);
});

test('a derived agency override survives as derived, not as a constant', () => {
  const next = registryWithOverride(seamRegistry(), 'recgov-campgrounds', { subcategory: null, agency: { kind: 'derived', field: 'X.Y' } });
  assert.deepEqual(next.poiData[1].agency, { kind: 'derived', field: 'X.Y' });
});

test('a slug no poi_data row owns keeps the registry, and refuses an override it could not apply', () => {
  const registry = seamRegistry();
  assert.equal(registryWithOverride(registry, 'recgov-campsites', { subcategory: null, agency: null }), registry);
  assert.throws(
    () => registryWithOverride(registry, 'recgov-campsites', { subcategory: 'state', agency: null }),
    /no poi_data row has terminal etl 'recgov-campsites'/,
  );
});

test('the seam check passes a case whose slug, adapter name and read args all match the registry', () => {
  assert.deepEqual(problemsFor([seamCase({})]), []);
  const aspiraEntry = etlEntryFor(seamRegistry(), 'aspira-wa-campgrounds');
  const aspira = seamCase({
    adapter: 'aspira-wa-campgrounds',
    slug: 'aspira-wa-campgrounds',
    registry: { subcategory: null, agency: null, args: { tenant: 'wa' } },
    instance: instanceFingerprint(new AspiraCampgroundsEtl('aspira-wa-campgrounds', 'wa', aspiraEntry.geometry)),
  });
  assert.deepEqual(problemsFor([aspira]), []);
});

test('the seam check reports a slug the registry does not declare', () => {
  const problems = problemsFor([seamCase({ slug: 'gone' })]);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /no etl with slug 'gone'/);
});

test('the seam check reports an adapters.json export the registry row does not name', () => {
  const adapters = { 'recgov-campgrounds': { export: 'RecGovCampsitesEtl' } };
  const problems = problemsFor([seamCase({})], seamRegistry(), adapters);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /exports 'RecGovCampsitesEtl' but registry etl 'recgov-campgrounds' names adapter 'RecGovCampgroundsEtl'/);
});

test('the seam check reports an arg the unit test read with a value the registry does not declare', () => {
  const wrongEntry = etlEntryFor(seamRegistry(), 'aspira-wa-campgrounds');
  const wrong = seamCase({
    adapter: 'aspira-wa-campgrounds',
    slug: 'aspira-wa-campgrounds',
    registry: { subcategory: null, agency: null, args: { tenant: 'bc' } },
    instance: instanceFingerprint(new AspiraCampgroundsEtl('aspira-wa-campgrounds', 'wa', wrongEntry.geometry)),
  });
  const problems = problemsFor([wrong]);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /args\.tenant='bc'.*registry declares 'wa'/);
  const absent = seamCase({ registry: { subcategory: null, agency: null, args: { host: 'x' } } });
  assert.equal(problemsFor([absent]).length, 1);
});

test('the same seam problem found in many cases is reported once', () => {
  const broken = Array.from({ length: 5 }, () => seamCase({ slug: 'gone' }));
  assert.equal(problemsFor(broken).length, 1);
});

test('the seam check refuses a case whose instance differs from what the registry row builds', () => {
  const stale = seamCase({ instance: instanceFingerprint(new RecGovCampgroundsEtl('something-else')) });
  const problems = problemsFor([stale]);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /the unit test ran .*something-else.*but the registry row 'recgov-campgrounds' builds/);
});

test('the instance check catches constructor data the slug and args checks cannot see', () => {
  const entry = etlEntryFor(seamRegistry(), 'aspira-wa-campgrounds');
  const synthetic = { ...entry.geometry, sources: entry.geometry.sources.slice(0, 1), match: { fuzzyThreshold: 0.9, parentFallback: false } };
  const built = seamCase({
    adapter: 'aspira-wa-campgrounds',
    slug: 'aspira-wa-campgrounds',
    registry: { subcategory: null, agency: null, args: {} },
    instance: instanceFingerprint(new AspiraCampgroundsEtl('aspira-wa-campgrounds', 'wa', synthetic)),
  });
  const problems = problemsFor([built]);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /not comparable with a replay of that row/);
});

test('the instance check reports an adapter the TypeScript registry does not have', () => {
  const problems = seamProblems([seamCase({})], seamRegistry(), seamAdapters, {});
  assert.equal(problems.length, 1);
  assert.match(problems[0], /no adapter named 'RecGovCampgroundsEtl' in the TypeScript adapterRegistry/);
});

test('the fingerprint is key-order independent, and separates bigint from its decimal spelling', () => {
  const left = { b: 2, a: { d: 4n, c: 3 } };
  const right = { a: { c: 3, d: 4n }, b: 2 };
  assert.equal(instanceFingerprint({ constructor: { name: 'X' }, ...left }), instanceFingerprint({ constructor: { name: 'X' }, ...right }));
  assert.notEqual(
    instanceFingerprint({ constructor: { name: 'X' }, v: 4n }),
    instanceFingerprint({ constructor: { name: 'X' }, v: '4' }),
  );
});

test('the fingerprint ignores methods, so a class gaining one does not invalidate every case', () => {
  const plain = { constructor: { name: 'X' }, etlSlug: 's' };
  assert.equal(instanceFingerprint(plain), instanceFingerprint({ ...plain, helper: () => 1 }));
});
