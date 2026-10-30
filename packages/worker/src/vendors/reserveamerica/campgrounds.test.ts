import { BookingProvider, DataProviderRef } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { loadPoiRegistry, type PoiDataEntry, type PoiRegistry } from '../../registry/poi-registry.js';
import type { ReserveAmericaDto } from './dto.js';
import { ReserveAmericaCampgroundsEtl } from './campgrounds.js';

const AB_SLUG = 'reserveamerica-ab-campgrounds';
const NY_SLUG = 'reserveamerica-ny-campgrounds';
const AB_INPUT = 'reserveamerica-abpp';
const NY_INPUT = 'reserveamerica-ny';
const RAW_DIR = '/unused';
const HTTP_OK = 200;
const FETCHED_AT = '2026-06-17T00:00:00Z';
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const PRODUCTION_CTX = TransformCtx.load(RAW_DIR, loadPoiRegistry(PRODUCTION_REGISTRY_PATH));
const NO_BREAK_SPACE = String.fromCodePoint(0xa0);
const LINE_SEPARATOR = String.fromCodePoint(0x2028);
const BYTE_ORDER_MARK = String.fromCodePoint(0xfeff);
const UNIT_SEPARATOR = String.fromCodePoint(0x1f);
const FILE_SEPARATOR = String.fromCodePoint(0x1c);
const VERTICAL_TAB = String.fromCodePoint(0x0b);
const FORM_FEED = String.fromCodePoint(0x0c);
const ARABIC_INDIC_SEVEN = String.fromCodePoint(0x667);
const CAPTURE_ROOT = decodeURIComponent(new URL('../../../../../data/raw/', import.meta.url).pathname);
const AB_CAPTURE_DIR = 'reserveamerica-abpp/2026-06-08T15-00-19Z';
const NY_CAPTURE_DIR = 'reserveamerica-ny/2026-06-22T15-20-19Z';

const KOTLIN_WHITESPACE_CODE_POINTS = [
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0xa0, 0x1680, 0x2000, 0x2001, 0x2005, 0x2007, 0x200a,
  0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
];
const NOT_KOTLIN_WHITESPACE_CODE_POINTS = [0x00, 0x01, 0x85, 0x180e, 0x200b, 0x200c, 0x2060, 0xfeff];

interface PageFields {
  lat?: string | null;
  lon?: string | null;
  title?: string | null;
  description?: string | null;
  image?: string | null;
  url?: string | null;
  phone?: string | null;
  gap?: string;
}

function meta(property: string, content: string | null, gap: string): string {
  return content === null ? '' : `<meta property="${property}"${gap}content='${content}' />\n`;
}

function page(fields: PageFields = {}): string {
  const gap = fields.gap ?? '  ';
  const lat = fields.lat === undefined ? '43.7597' : fields.lat;
  const lon = fields.lon === undefined ? '-74.7244' : fields.lon;
  const title = fields.title === undefined ? 'ALGER ISLAND, NY' : fields.title;
  const description = fields.description === undefined ? 'Island campground on Fourth Lake.' : fields.description;
  const image = fields.image === undefined ? 'https://example.test/photo.jpg' : fields.image;
  const url = fields.url === undefined ? 'https://example.test/camping/r/campgroundDetails.do?parkId=695' : fields.url;
  const phone = fields.phone === undefined ? '1-518-555-0100' : fields.phone;
  return [
    '<html><head>\n',
    meta('place:location:latitude', lat, gap),
    meta('place:location:longitude', lon, gap),
    meta('og:title', title, gap),
    meta('og:description', description, gap),
    meta('og:image', image, gap),
    meta('og:url', url, gap),
    '</head><body>',
    phone === null ? '' : `<span itemprop="telephone" >${phone}</span>`,
    '</body></html>',
  ].join('');
}

function envelopeOf(payload: unknown, part: string | undefined): Envelope {
  return {
    fetcher: 'f',
    fetcherVersion: '1',
    fetchedAt: FETCHED_AT,
    request: { url: 'https://example.test/', method: 'GET', headers: {} },
    response: { status: HTTP_OK, headers: {} },
    pollerRunId: null,
    payload,
    ...(part === undefined ? {} : { part }),
  };
}

interface Capture {
  part?: string;
  payload: unknown;
}

function bundleOf(input: string, captures: Capture[]): InputBundle {
  return new InputBundle(new Map([[input, captures.map((capture) => envelopeOf(capture.payload, capture.part))]]));
}

function parkCapture(id: string, fields: PageFields = {}): Capture {
  return { part: `park-${id}`, payload: page(fields) };
}

function parsedOf(slug: string, contract: string, input: string, captures: Capture[]): ParseResult<ReserveAmericaDto>[] {
  return [...new ReserveAmericaCampgroundsEtl(slug, contract).parse(bundleOf(input, captures))];
}

function transformedOf(
  slug: string,
  contract: string,
  input: string,
  captures: Capture[],
): TransformResult<CampgroundUpsertCandidate>[] {
  const etl = new ReserveAmericaCampgroundsEtl(slug, contract);
  const results: TransformResult<CampgroundUpsertCandidate>[] = [];
  for (const result of etl.parse(bundleOf(input, captures))) {
    if (!result.ok) throw new Error(result.errors.join());
    results.push(...etl.transform(result.dto, PRODUCTION_CTX));
  }
  return results;
}

function recordsOf(results: TransformResult<CampgroundUpsertCandidate>[]): CampgroundUpsertCandidate[] {
  return results.map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function nyRecords(captures: Capture[]): CampgroundUpsertCandidate[] {
  return recordsOf(transformedOf(NY_SLUG, 'NY', NY_INPUT, captures));
}

function abRecords(captures: Capture[]): CampgroundUpsertCandidate[] {
  return recordsOf(transformedOf(AB_SLUG, 'ABPP', AB_INPUT, captures));
}

function nyRecord(fields: PageFields): CampgroundUpsertCandidate {
  const [only, ...rest] = nyRecords([parkCapture('695', fields)]);
  if (only === undefined || rest.length > 0) throw new Error('expected exactly one record');
  return only;
}

function abRecord(fields: PageFields): CampgroundUpsertCandidate {
  const [only, ...rest] = abRecords([parkCapture('330101', fields)]);
  if (only === undefined || rest.length > 0) throw new Error('expected exactly one record');
  return only;
}

function parkIdsOf(captures: Capture[]): string[] {
  const results = parsedOf(NY_SLUG, 'NY', NY_INPUT, captures);
  return results.flatMap((result) => (result.ok ? result.dto.parks.map((park) => park.parkId.toString()) : []));
}

function payloadOf(record: CampgroundUpsertCandidate): Record<string, unknown> {
  return JSON.parse(record.sourcePayload as string) as Record<string, unknown>;
}

const SOME_AGENCY: PoiDataEntry['agency'] = { kind: 'constant', value: 'Some Agency' };

function ctxWith(args: Record<string, string>, agency: PoiDataEntry['agency'] | null = SOME_AGENCY): TransformCtx {
  const poiData: PoiDataEntry[] = [
    {
      name: 'Variant',
      enabled: true,
      category: 'campground',
      subcategory: 'state',
      ...(agency === null ? {} : { agency }),
      etls: [{ slug: 'variant', adapter: 'ReserveAmericaCampgroundsEtl', inputs: [NY_INPUT], args }],
    },
  ];
  const registry: PoiRegistry = {
    dataSources: [],
    poiData,
    campsiteData: [],
    bookingProviders: [],
    dataSource: () => undefined,
    poiDataByName: (name) => poiData.find((row) => row.name === name),
    campsiteDataByName: () => undefined,
  };
  return TransformCtx.load(RAW_DIR, registry);
}

function transformDirect(
  args: Record<string, string>,
  title = 'Foo, NY',
  agency: PoiDataEntry['agency'] | null = SOME_AGENCY,
): CampgroundUpsertCandidate {
  const dto: ReserveAmericaDto = {
    parks: [
      {
        parkId: 5n,
        name: title,
        lat: 1.5,
        lon: -2.5,
        phone: null,
        description: null,
        photoUrl: null,
        infoUrl: null,
      },
    ],
  };
  const results = [...new ReserveAmericaCampgroundsEtl('variant', 'NY').transform(dto, ctxWith(args, agency))];
  const [only] = results;
  if (only === undefined || !only.ok) throw new Error('expected one ok record');
  return only.record;
}

describe('the production registry rows', () => {
  test('Alberta takes every default and New York states its own region and country', () => {
    expect(PRODUCTION_CTX.subcategoryFor(AB_SLUG)).toBe('provincial');
    expect(PRODUCTION_CTX.argFor(AB_SLUG, 'region')).toBeUndefined();
    expect(PRODUCTION_CTX.argFor(AB_SLUG, 'contract')).toBe('ABPP');
    expect(PRODUCTION_CTX.subcategoryFor(NY_SLUG)).toBe('state');
    expect(PRODUCTION_CTX.argFor(NY_SLUG, 'region')).toBe('NY');
    expect(PRODUCTION_CTX.argFor(NY_SLUG, 'country')).toBe('US');
    expect(PRODUCTION_CTX.argFor(NY_SLUG, 'contract')).toBe('NY');
  });
});

describe('transform, ported from the Kotlin ReserveAmericaCampgroundsEtlTest', () => {
  test('New York args stamp state campground metadata with the ReserveAmerica provider ref', () => {
    const record = nyRecord({
      title: 'ALGER ISLAND, NY',
      url: 'https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695',
      image: 'https://newyorkstateparks.reserveamerica.com/photo.jpg',
    });

    expect(record.dataProviderRef).toEqual(DataProviderRef.reserveAmerica('ra-695'));
    expect(record.dataProviderRef.serialize()).toBe('ra-695');
    expect(record.bookingProvider).toBe(BookingProvider.RESERVEAMERICA);
    expect(record.bookingProviderRef).toBe('NY:695');
    expect(record.name).toBe('ALGER ISLAND');
    expect(record.latitude).toBe(43.7597);
    expect(record.longitude).toBe(-74.7244);
    expect(record.location).toEqual({ latitude: 43.7597, longitude: -74.7244, region: 'NY', country: 'US' });
    expect(record.kind).toBe('state');
    expect(record.management).toEqual({ agency: 'New York State Parks' });
    expect(record.reservationUrl).toBe(
      'https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695',
    );
    expect(record.sourceUrl).toBe(record.reservationUrl);
    expect(record.links).toEqual([{ url: record.reservationUrl }]);
    expect(record.mediumDescription).toBe('Island campground on Fourth Lake.');
    expect(record.photos).toEqual([{ url: 'https://newyorkstateparks.reserveamerica.com/photo.jpg' }]);
    expect(record.metadata ?? null).toBeNull();
    expect(record.parentName ?? null).toBeNull();
    expect(record.amenities ?? []).toEqual([]);
    expect(record.cellService ?? []).toEqual([]);
    expect(payloadOf(record)).toMatchObject({
      contract: 'NY',
      name: 'ALGER ISLAND',
      description: 'Island campground on Fourth Lake.',
    });
  });

  test('Alberta defaults preserve the existing source shape and the ReserveAmerica provider ref', () => {
    const record = abRecord({
      title: 'Writing-on-Stone Provincial Park, AB',
      lat: '49.083',
      lon: '-111.617',
      phone: null,
      description: null,
      image: null,
      url: 'https://shop.albertaparks.ca/camping/x/r/campgroundDetails.do?contractCode=ABPP&parkId=123',
    });

    expect(record.dataProviderRef.serialize()).toBe('ra-330101');
    expect(record.bookingProviderRef).toBe('ABPP:330101');
    expect(record.name).toBe('Writing-on-Stone Provincial Park');
    expect(record.location).toEqual({ latitude: 49.083, longitude: -111.617, region: 'AB', country: 'CA' });
    expect(record.kind).toBe('provincial');
    expect(record.management).toEqual({ agency: 'Alberta Parks' });
    expect(record.mediumDescription).toBeNull();
    expect(record.photos).toEqual([]);
  });
});

describe('which envelopes become parks', () => {
  test('only park-<long> parts count: directory pages, unlabelled pages and malformed ids are skipped', () => {
    const captures: Capture[] = [
      { part: 'directory-A-000', payload: page() },
      { payload: page() },
      { part: 'Park-1', payload: page() },
      { part: 'park-', payload: page() },
      { part: 'park-abc', payload: page() },
      { part: 'park-1.5', payload: page() },
      { part: 'park-9223372036854775808', payload: page() },
      { part: 'park- 7', payload: page() },
      parkCapture('695'),
    ];

    expect(parkIdsOf(captures)).toEqual(['695']);
  });

  test('the id is read the way String.toLongOrNull reads it: a plus sign, leading zeros and other scripts digits', () => {
    expect(parkIdsOf([parkCapture('+7'), parkCapture('007'), parkCapture(ARABIC_INDIC_SEVEN), parkCapture('-3')])).toEqual([
      '7',
      '7',
      '7',
      '-3',
    ]);
  });

  test('two parts naming the same park both come through, as the repository decides what a repeat means', () => {
    const records = nyRecords([parkCapture('7'), parkCapture('007')]);

    expect(records.map((record) => record.dataProviderRef.serialize())).toEqual(['ra-7', 'ra-7']);
  });

  test('an id past 2^53 keeps every digit in the ref, the booking ref and the payload', () => {
    const [record] = nyRecords([parkCapture('9007199254740993')]);

    expect(record?.dataProviderRef.serialize()).toBe('ra-9007199254740993');
    expect(record?.bookingProviderRef).toBe('NY:9007199254740993');
    expect(record?.sourcePayload).toContain('"park_id":9007199254740993,');
  });

  test('a page with no parks parsed is a bad parse naming the slug, not an empty success', () => {
    const results = parsedOf(NY_SLUG, 'NY', NY_INPUT, [{ part: 'directory-A-000', payload: page() }]);

    expect(results).toEqual([{ ok: false, errors: [`${NY_SLUG}: no park pages parsed`] }]);
  });

  test('an input with no envelopes is refused outright', () => {
    expect(() => parsedOf(NY_SLUG, 'NY', NY_INPUT, [])).toThrow(`${NY_SLUG}: no pages`);
  });

  test('more than one declared input is refused, since the adapter reads the sole one', () => {
    const bundle = new InputBundle(
      new Map([
        [NY_INPUT, [envelopeOf(page(), 'park-1')]],
        ['other', [envelopeOf(page(), 'park-2')]],
      ]),
    );

    expect(() => [...new ReserveAmericaCampgroundsEtl(NY_SLUG, 'NY').parse(bundle)]).toThrow();
  });

  test('a payload that is a JSON primitive other than a string reads as its text, and a container throws', () => {
    expect(parsedOf(NY_SLUG, 'NY', NY_INPUT, [{ part: 'park-1', payload: 5 }])[0]?.ok).toBe(false);
    expect(parsedOf(NY_SLUG, 'NY', NY_INPUT, [{ part: 'park-1', payload: null }])[0]?.ok).toBe(false);
    expect(() => parsedOf(NY_SLUG, 'NY', NY_INPUT, [{ part: 'park-1', payload: {} }])).toThrow();
    expect(() => parsedOf(NY_SLUG, 'NY', NY_INPUT, [{ part: 'park-1', payload: [] }])).toThrow();
  });

  test('a payload container on a page that is skipped anyway is never read', () => {
    const results = parsedOf(NY_SLUG, 'NY', NY_INPUT, [{ part: 'directory-A-000', payload: {} }, parkCapture('1')]);

    expect(results[0]?.ok).toBe(true);
  });
});

describe('a park page that cannot be read is dropped without a trace', () => {
  test.each([
    ['no latitude', { lat: null }],
    ['no longitude', { lon: null }],
    ['no title', { title: null }],
    ['a blank title', { title: '   ' }],
    ['a title of only Kotlin whitespace', { title: `${UNIT_SEPARATOR}${FILE_SEPARATOR}` }],
    ['a latitude that is not a number', { lat: 'north' }],
    ['a longitude that is not a number', { lon: '' + '12,5' }],
    ['a hexadecimal latitude without a binary exponent', { lat: '0x10' }],
    ['a latitude with an underscore', { lat: '4_3' }],
    ['a latitude in another script', { lat: ARABIC_INDIC_SEVEN }],
  ] as [string, PageFields][])('%s', (_label, fields) => {
    expect(parkIdsOf([parkCapture('1', fields)])).toEqual([]);
  });

  test('an empty latitude attribute does not match, since the value must have a character', () => {
    expect(parkIdsOf([parkCapture('1', { lat: '' })])).toEqual([]);
  });
});

describe('the meta patterns match the way a Java regex does', () => {
  test.each([
    ['a space', ' '],
    ['two spaces', '  '],
    ['a tab', '\t'],
    ['a newline', '\n'],
    ['a carriage return and newline', '\r\n'],
    ['a vertical tab', VERTICAL_TAB],
    ['a form feed', FORM_FEED],
  ])('%s between the property and its content is whitespace', (_label, gap) => {
    expect(parkIdsOf([parkCapture('1', { gap })])).toEqual(['1']);
  });

  test.each([
    ['a no-break space', NO_BREAK_SPACE],
    ['a line separator', LINE_SEPARATOR],
    ['a byte order mark', BYTE_ORDER_MARK],
    ['nothing at all', ''],
  ])('%s between the property and its content is not', (_label, gap) => {
    expect(parkIdsOf([parkCapture('1', { gap })])).toEqual([]);
  });

  test('a property followed by a different one never satisfies the pattern', () => {
    const html = `<meta property="og:title:alt"  content='x' /><meta property="place:location:latitude" content='1' /><meta property="place:location:longitude" content='2' />`;

    expect(parkIdsOf([{ part: 'park-1', payload: html }])).toEqual([]);
  });

  test('the first match wins when a property is repeated', () => {
    const html = page({ title: 'First, NY' }) + `<meta property="og:title" content='Second, NY' />`;

    expect(nyRecords([{ part: 'park-1', payload: html }])[0]?.name).toBe('First');
  });
});

describe('numbers', () => {
  test.each([
    ['padded with spaces', ' 43.5 ', 43.5],
    ['padded with control characters', '\u0001 43.5\u001f', 43.5],
    ['with an exponent', '4.35e1', 43.5],
    ['with a float suffix', '43.5f', 43.5],
    ['with a double suffix', '43.5D', 43.5],
    ['with a leading plus', '+43.5', 43.5],
    ['without an integer part', '.5', 0.5],
    ['without a fraction', '43.', 43],
    ['whole', '44', 44],
    ['hexadecimal with a binary exponent', '0x1p3', 8],
  ])('a latitude %s', (_label, lat, expected) => {
    expect(nyRecord({ lat }).latitude).toBe(expected);
  });

  test('a non-finite coordinate is read as a number and then refused when the payload is written', () => {
    for (const lat of ['NaN', 'Infinity', '-Infinity', '1e999']) {
      const etl = new ReserveAmericaCampgroundsEtl(NY_SLUG, 'NY');
      const [parsed] = [...etl.parse(bundleOf(NY_INPUT, [parkCapture('1', { lat })]))];
      if (parsed === undefined || !parsed.ok) throw new Error(`${lat} should have parsed`);
      expect(() => [...etl.transform(parsed.dto, PRODUCTION_CTX)]).toThrow();
    }
  });

  test('a coordinate that is not finite stops the run after the parks before it have been yielded', () => {
    const etl = new ReserveAmericaCampgroundsEtl(NY_SLUG, 'NY');
    const [parsed] = [...etl.parse(bundleOf(NY_INPUT, [parkCapture('1'), parkCapture('2', { lat: 'NaN' }), parkCapture('3')]))];
    if (parsed === undefined || !parsed.ok) throw new Error('should have parsed');
    const seen: string[] = [];

    expect(() => {
      for (const result of etl.transform(parsed.dto, PRODUCTION_CTX)) {
        if (result.ok) seen.push(result.record.dataProviderRef.serialize());
      }
    }).toThrow();
    expect(seen).toEqual(['ra-1']);
  });
});

describe('real captures', () => {
  function captured(directory: string, file: string): Capture {
    const envelope = parseEnvelope(`${CAPTURE_ROOT}${directory}/${file}`);
    return { ...(envelope.part === undefined ? {} : { part: envelope.part }), payload: envelope.payload };
  }

  test.each([
    ['park-330101.json', 'Aspen Beach Provincial Park', 52.4569444, -113.9777778, '1-877-537-2757'],
    ['park-331050.json', 'Peaceful Valley Provincial Recreation Area', 52.9330556, -114.0830556, '(403)748-3939'],
    ['park-330119.json', 'Fish Creek Provincial Park', 50.9094444, -114.0202778, '(403)678-0760'],
  ])('the Alberta page %s', (file, name, lat, lon, phone) => {
    const [record] = abRecords([captured(AB_CAPTURE_DIR, file)]);

    expect(record?.name).toBe(name);
    expect(record?.latitude).toBe(lat);
    expect(record?.longitude).toBe(lon);
    expect(record?.location).toEqual({ latitude: lat, longitude: lon, region: 'AB', country: 'CA' });
    expect(record?.bookingProviderRef).toBe(`ABPP:${file.replace(/\D/g, '')}`);
    expect(payloadOf(record as CampgroundUpsertCandidate).phone).toBe(phone);
  });

  test('the New York page park-2116.json', () => {
    const [record] = nyRecords([captured(NY_CAPTURE_DIR, 'park-2116.json')]);

    expect(record?.name).toBe('DeVeaux Woods State Park');
    expect(record?.location).toEqual({ latitude: 43.1192139, longitude: -79.057425, region: 'NY', country: 'US' });
    expect(record?.photos).toEqual([{ url: 'https://newyorkstateparks.reserveamerica.com/webphotos/NY/pid2116/0/540x360.jpg' }]);
    expect(record?.mediumDescription).toBe('De Veaux Woods State Park');
  });

  test('a New York day-use page with no coordinates is dropped rather than placed at zero', () => {
    const results = parsedOf(NY_SLUG, 'NY', NY_INPUT, [captured(NY_CAPTURE_DIR, 'park-10885.json')]);

    expect(results).toEqual([{ ok: false, errors: [`${NY_SLUG}: no park pages parsed`] }]);
  });
});

describe('Kotlin whitespace at the edges of a field', () => {
  const codePointLabel = (codePoint: number): string => `U+${codePoint.toString(16).toUpperCase().padStart(4, '0')}`;

  test.each(KOTLIN_WHITESPACE_CODE_POINTS.map((codePoint) => [codePointLabel(codePoint), String.fromCodePoint(codePoint)]))(
    '%s is trimmed from a title, a description and a phone',
    (_label, edge) => {
      const record = nyRecord({ title: `${edge}ALGER, NY${edge}`, description: `${edge}d${edge}`, phone: `${edge}p${edge}` });

      expect(record.name).toBe('ALGER');
      expect(record.mediumDescription).toBe('d');
      expect(payloadOf(record).phone).toBe('p');
    },
  );

  test.each(NOT_KOTLIN_WHITESPACE_CODE_POINTS.map((codePoint) => [codePointLabel(codePoint), String.fromCodePoint(codePoint)]))(
    '%s is not trimmed from a title, a description or a phone',
    (_label, edge) => {
      const record = nyRecord({ title: `${edge}ALGER, NY${edge}`, description: `${edge}d${edge}`, phone: `${edge}p${edge}` });

      expect(record.name).toBe(`${edge}ALGER, NY${edge}`);
      expect(record.mediumDescription).toBe(`${edge}d${edge}`);
      expect(payloadOf(record).phone).toBe(`${edge}p${edge}`);
    },
  );

  test.each(KOTLIN_WHITESPACE_CODE_POINTS.map((codePoint) => [codePointLabel(codePoint), String.fromCodePoint(codePoint)]))(
    '%s alone makes a title, a description, a phone and a photo url blank',
    (_label, edge) => {
      expect(parkIdsOf([parkCapture('1', { title: edge })])).toEqual([]);
      const record = nyRecord({ description: edge, phone: edge, image: edge, url: edge });

      expect(record.mediumDescription).toBeNull();
      expect(record.photos).toEqual([]);
      expect(record.reservationUrl).toBeNull();
      expect('phone' in payloadOf(record)).toBe(false);
    },
  );
});

describe('the name', () => {
  test.each([
    ['strips the state suffix', 'ALGER ISLAND, NY', 'ALGER ISLAND'],
    ['strips it and trims what is left', 'ALGER ISLAND , NY', 'ALGER ISLAND'],
    ['keeps a different suffix', 'ALGER ISLAND, AB', 'ALGER ISLAND, AB'],
    ['keeps the suffix when it is not at the end', 'A, NY Park', 'A, NY Park'],
    ['matches the suffix case-sensitively', 'ALGER ISLAND, ny', 'ALGER ISLAND, ny'],
    ['trims the title by Kotlin whitespace first', `${UNIT_SEPARATOR}ALGER, NY${FILE_SEPARATOR}`, 'ALGER'],
    ['leaves a byte order mark in place, so the suffix is no longer at the end', `ALGER, NY${BYTE_ORDER_MARK}`, `ALGER, NY${BYTE_ORDER_MARK}`],
    ['keeps entities as the page wrote them', 'Fish &amp; Game, NY', 'Fish &amp; Game'],
    ['can leave an empty name when the title is only the suffix', ', NY', ''],
  ])('%s', (_label, title, expected) => {
    expect(nyRecord({ title }).name).toBe(expected);
  });

  test('the payload carries the stripped name', () => {
    expect(payloadOf(nyRecord({ title: 'ALGER ISLAND, NY' })).name).toBe('ALGER ISLAND');
  });
});

describe('optional fields', () => {
  test('a phone is trimmed, carried only in the payload and absent when blank', () => {
    const present = nyRecord({ phone: `${UNIT_SEPARATOR} 1-518-555-0100 ` });
    const blank = nyRecord({ phone: '  ' });

    expect(payloadOf(present).phone).toBe('1-518-555-0100');
    expect(present.contact ?? null).toBeNull();
    expect('phone' in payloadOf(blank)).toBe(false);
  });

  test('the first telephone on the page is the one read, and attributes before the closing bracket are skipped', () => {
    const html = page({ phone: null }) + `<span class="x" itemprop="telephone" data-a="b">111</span><span itemprop="telephone">222</span>`;

    expect(payloadOf(nyRecords([{ part: 'park-1', payload: html }])[0] as CampgroundUpsertCandidate).phone).toBe('111');
  });

  test('a description is trimmed and kept raw, and an empty or blank one is dropped', () => {
    expect(nyRecord({ description: '  A &amp; B  ' }).mediumDescription).toBe('A &amp; B');
    expect(nyRecord({ description: '' }).mediumDescription).toBeNull();
    expect(nyRecord({ description: ' \t ' }).mediumDescription).toBeNull();
    expect('description' in payloadOf(nyRecord({ description: '' }))).toBe(false);
  });

  test('a photo and an info url are kept untrimmed, and dropped when blank', () => {
    const padded = nyRecord({ image: ' https://example.test/p.jpg ', url: ' https://example.test/u ' });
    const blank = nyRecord({ image: ' ', url: ' ' });

    expect(padded.photos).toEqual([{ url: ' https://example.test/p.jpg ' }]);
    expect(padded.reservationUrl).toBe(' https://example.test/u ');
    expect(blank.photos).toEqual([]);
    expect(blank.links).toEqual([]);
    expect(blank.reservationUrl).toBeNull();
    expect(blank.sourceUrl).toBeNull();
  });

  test('a photo url of only information separators is not blank to the pattern but is to Kotlin', () => {
    expect(nyRecord({ image: `${UNIT_SEPARATOR}` }).photos).toEqual([]);
  });

  test('the payload lists the parsed fields in declaration order and leaves out what is absent', () => {
    const full = nyRecord({});
    const bare = nyRecord({ phone: null, description: null, image: null, url: null });

    expect(Object.keys(payloadOf(full))).toEqual([
      'contract',
      'park_id',
      'name',
      'latitude',
      'longitude',
      'phone',
      'description',
      'photo_url',
      'info_url',
    ]);
    expect(Object.keys(payloadOf(bare))).toEqual(['contract', 'park_id', 'name', 'latitude', 'longitude']);
    expect(payloadOf(full).park_id).toBe(695);
  });
});

describe('the settings arguments', () => {
  test('region, country, suffix and id prefix each override their default', () => {
    const record = transformDirect(
      { region: 'ON', country: 'CA', title_suffix: ' (Ontario)', source_id_prefix: 'on' },
      'Algonquin (Ontario)',
    );

    expect(record.name).toBe('Algonquin');
    expect(record.location.region).toBe('ON');
    expect(record.location.country).toBe('CA');
    expect(record.dataProviderRef.serialize()).toBe('on-5');
  });

  test('with no arguments the suffix follows the default region and the country is Canada', () => {
    const record = transformDirect({}, 'Foo, AB');

    expect(record.name).toBe('Foo');
    expect(record.location).toEqual({ latitude: 1.5, longitude: -2.5, region: 'AB', country: 'CA' });
    expect(record.dataProviderRef.serialize()).toBe('ra-5');
  });

  test('the default suffix follows an overridden region', () => {
    expect(transformDirect({ region: 'BC' }, 'Foo, BC').name).toBe('Foo');
  });

  test('a blank suffix strips nothing', () => {
    expect(transformDirect({ title_suffix: '  ' }, 'Foo,   ').name).toBe('Foo,');
    expect(transformDirect({ title_suffix: '' }, 'Foo, AB').name).toBe('Foo, AB');
  });

  test('an empty region argument is kept as the empty string rather than falling back', () => {
    const record = transformDirect({ region: '' }, 'Foo, x');

    expect(record.location.region).toBe('');
    expect(record.name).toBe('Foo, x');
  });

  test('the provider argument is trimmed and lower-cased before it is compared', () => {
    expect(transformDirect({ provider: '  ReserveAmerica ' }).bookingProvider).toBe(BookingProvider.RESERVEAMERICA);
    expect(transformDirect({ provider: 'RESERVEAMERICA' }).bookingProviderRef).toBe('NY:5');
  });

  test('another known provider, or an unknown one, leaves the campground without a booking provider', () => {
    for (const provider of ['recgov', 'aspira', 'nonsense', '']) {
      const record = transformDirect({ provider });
      expect(record.bookingProvider).toBeNull();
      expect(record.bookingProviderRef).toBeNull();
    }
  });

  test('a missing constant agency is refused, and so is one derived from a field', () => {
    const dto: ReserveAmericaDto = { parks: [] };
    const etl = new ReserveAmericaCampgroundsEtl('variant', 'NY');

    expect(() => [...etl.transform(dto, ctxWith({}, null))]).toThrow('variant is missing required poi_data.agency');
    expect(() => [...etl.transform(dto, ctxWith({}, { kind: 'derived', field: 'X' }))]).toThrow("not a constant agency");
  });
});
