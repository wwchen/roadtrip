// Reading a campsite row into facts.
//
// All of this is provider normalisation, so the tests are mostly "the same fact spelled
// four ways yields one answer". The two rules worth reading are the image search (which
// must not promote a booking link or a campground map to a photo) and the attribute
// flattening (which must not print our own unresolved definition ids at a camper).
import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import {
  attributeLabels,
  capacityLabel,
  descriptionText,
  detailFacts,
  featureLabels,
  findImageUrl,
  firstString,
  formatValue,
} from './site-detail-facts';

const site = (extra: Partial<Campsite> = {}): Partial<Campsite> => ({ id: 1, ...extra });
const facts = (s: Partial<Campsite>) => Object.fromEntries(detailFacts(s).map((f) => [f.label, f.value]));

describe('capacity', () => {
  test('reads a range, a ceiling and a floor as different claims', () => {
    expect(capacityLabel(site(), { min_capacity: 2, max_capacity: 6 })).toBe('2-6 people');
    expect(capacityLabel(site(), { max_capacity: 6 })).toBe('Up to 6 people');
    expect(capacityLabel(site(), { min_capacity: 2 })).toBe('2+ people');
  });

  test('collapses a range whose ends agree', () => {
    expect(capacityLabel(site(), { min_capacity: 6, max_capacity: 6 })).toBe('Up to 6 people');
  });

  // The same number from four vendors.
  test('accepts every spelling', () => {
    expect(capacityLabel(site(), { minCapacity: 2, maxCapacity: 6 })).toBe('2-6 people');
    expect(capacityLabel(site(), { min_num_people: 2, max_num_people: 6 })).toBe('2-6 people');
    expect(capacityLabel(site(), { minNumPeople: 2, maxNumPeople: 6 })).toBe('2-6 people');
  });

  test('prefers the promoted column over the payload', () => {
    expect(capacityLabel(site({ max_people: 4 }), { max_capacity: 99 })).toBe('Up to 4 people');
  });

  test('reads numbers written as strings', () => {
    expect(capacityLabel(site(), { max_capacity: '6' })).toBe('Up to 6 people');
  });

  test('says nothing when it knows nothing', () => {
    expect(capacityLabel(site(), {})).toBe('');
    expect(capacityLabel(site(), { max_capacity: 'lots' })).toBe('');
  });
});

describe('the fact list', () => {
  test('promotes what a camper checks first', () => {
    expect(
      facts(
        site({
          loop_name: 'Upper Loop',
          kind_listed: 'Walk-in tent',
          max_people: 6,
          data_provider: 'recgov',
          data_provider_ref: '4321',
          source_payload: { type_of_use: 'Overnight', campsite_reserve_type: 'Site-specific' },
        }),
      ),
    ).toEqual({
      Loop: 'Upper Loop',
      Type: 'Walk-in tent',
      Capacity: 'Up to 6 people',
      Reserve: 'Site-specific',
      Use: 'Overnight',
      Provider: 'recgov',
      'Provider ID': '4321',
    });
  });

  test('drops facts it has no value for', () => {
    expect(Object.keys(facts(site()))).toEqual([]);
  });

  test('falls back through the payload for the loop and type', () => {
    expect(facts(site({ source_payload: { loop: 'B', site_type: 'RV' } }))).toMatchObject({
      Loop: 'B',
      Type: 'RV',
    });
  });

  test('lists at most four equipment types', () => {
    expect(
      facts(site({ source_payload: { allowed_equipment: ['Tent', 'RV', 'Van', 'Trailer', 'Boat'] } })),
    ).toMatchObject({ Equipment: 'Tent, RV, Van, Trailer' });
  });
});

describe('feature chips', () => {
  // Only `true`: in these rows `false` usually means "no data", not "no firepit".
  test('include a true column and omit a false one', () => {
    const labels = featureLabels(site({ firepit: true, picnic_table: false }));

    expect(labels).toContain('Firepit');
    expect(labels).not.toContain('Picnic table');
  });

  test('format the measurements with units', () => {
    expect(featureLabels(site({ max_rv_length: 32, max_cars: 2 }))).toEqual(
      expect.arrayContaining(['Max RV length: 32 ft', 'Max cars: 2']),
    );
  });

  test('deduplicate the same fact arriving twice', () => {
    const labels = featureLabels(
      site({ firepit: true, source_payload: { attributes: [{ name: 'firepit' }] } }),
    );

    expect(labels.filter((label) => label.toLowerCase() === 'firepit')).toHaveLength(1);
  });

  test('are capped so a dense row stays readable', () => {
    const many = Array.from({ length: 40 }, (_, index) => ({ name: `Feature ${index}` }));

    expect(featureLabels(site({ source_payload: { attributes: many } }))).toHaveLength(12);
  });
});

describe('flattening provider attribute bags', () => {
  test('reads a name/value pair', () => {
    expect(attributeLabels([{ name: 'Shade', value: 'Full' }])).toEqual(['Shade: Full']);
  });

  // "Pets allowed: true" reads worse than "Pets allowed".
  test('drops a redundant boolean value', () => {
    expect(attributeLabels([{ name: 'Pets allowed', value: true }])).toEqual(['Pets allowed']);
  });

  test('recurses through nested arrays', () => {
    expect(attributeLabels([[{ name: 'A' }], [{ name: 'B' }]])).toEqual(['A', 'B']);
  });

  test('accepts bare strings', () => {
    expect(attributeLabels(['Shade', ''])).toEqual(['Shade']);
  });

  // Our own plumbing: an attribute that is only a reference we never resolved.
  test('skips an unresolved definition reference', () => {
    expect(attributeLabels([{ definition_id: 41, value: 'x' }])).toEqual([]);
    expect(attributeLabels([{ attributeDefinitionId: 41 }])).toEqual([]);
  });

  test('humanises an anonymous object"s keys, minus its id', () => {
    expect(attributeLabels([{ id: 9, max_shade: 'Full' }])).toEqual(['Max Shade: Full']);
  });

  test('strips markup out of provider text', () => {
    expect(attributeLabels(['<b>Shade</b>  full'])).toEqual(['Shade full']);
  });
});

describe('the description', () => {
  test('is plain text, clamped', () => {
    expect(descriptionText('<p>Waterfront   site.</p>')).toBe('Waterfront site.');
    expect(descriptionText('x'.repeat(400))).toHaveLength(260);
    expect(descriptionText('x'.repeat(400)).endsWith('...')).toBe(true);
  });

  test('is empty for nothing useful', () => {
    expect(descriptionText(null)).toBe('');
    expect(descriptionText('   ')).toBe('');
  });
});

describe('finding an image', () => {
  test('takes a URL whose key says image', () => {
    expect(findImageUrl(site({ source_payload: { image_url: 'https://cdn.example/a' } }))).toBe(
      'https://cdn.example/a',
    );
  });

  test('takes a URL whose extension says image', () => {
    expect(findImageUrl(site({ source_payload: { hero: 'https://cdn.example/a.jpg?v=2' } }))).toBe(
      'https://cdn.example/a.jpg?v=2',
    );
  });

  // A campground map is not a photo of the site.
  test('skips anything keyed as a map', () => {
    expect(findImageUrl(site({ source_payload: { map_url: 'https://cdn.example/m.png' } }))).toBe('');
  });

  // Otherwise the first https string in the payload becomes the hero, and that is
  // usually a booking link.
  test('ignores a plain link', () => {
    expect(findImageUrl(site({ source_payload: { url: 'https://recreation.gov/x' } }))).toBe('');
  });

  test('searches nested structures', () => {
    expect(
      findImageUrl(site({ source_payload: { media: [{ thumbnail: 'https://cdn.example/t.webp' }] } })),
    ).toBe('https://cdn.example/t.webp');
  });

  test('survives a cyclic payload', () => {
    const payload: Record<string, unknown> = { name: 'x' };
    payload.self = payload;

    expect(() => findImageUrl(site({ source_payload: payload }))).not.toThrow();
  });
});

describe('the small coercions', () => {
  test('formatValue treats absence and false as nothing', () => {
    expect(formatValue(null)).toBe('');
    expect(formatValue(false)).toBe('');
    expect(formatValue(true)).toBe('true');
    expect(formatValue(0)).toBe('0');
    expect(formatValue(NaN)).toBe('');
    expect(formatValue(['a', '', 'b'])).toBe('a, b');
    expect(formatValue({ label: 'Named' })).toBe('Named');
  });

  test('firstString takes the first non-blank, numbers included', () => {
    expect(firstString('', '   ', 'third')).toBe('third');
    expect(firstString(null, 42)).toBe('42');
    expect(firstString(null, undefined)).toBe('');
  });
});
