import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import {
  capacityLabel,
  capacityRange,
  descriptionText,
  detailFacts,
  featureLabels,
} from './site-detail-facts';

const site: Partial<Campsite> = {
  id: 1,
  name: 'Site 12',
  loop_name: 'Loop A',
  kind: 'standard',
  kind_label: 'Standard',
  kind_listed: 'STANDARD NONELECTRIC',
  min_people: 2,
  max_people: 6,
  equipment: ['Tent', 'RV', 'Trailer', 'Van', 'Boat'],
  attributes: [
    { name: 'Reserve type', value: 'Site-Specific' },
    { name: 'Pets allowed' },
    { name: 'Shade', value: 'Partial' },
  ],
  firepit: true,
  picnic_table: false,
  max_rv_length: 32,
  description: 'Walk-in tent site by the water.',
  photo_url: 'https://x/1.jpg',
  data_provider: 'recgov',
  data_provider_ref: '100',
};

describe('the fact list', () => {
  test('detail facts in reading order', () => {
    expect(detailFacts(site)).toEqual([
      { label: 'Loop', value: 'Loop A' },
      { label: 'Type', value: 'STANDARD NONELECTRIC' },
      { label: 'Capacity', value: '2-6 people' },
      { label: 'Equipment', value: 'Tent, RV, Trailer, Van' },
      { label: 'Provider', value: 'recgov' },
      { label: 'Provider ID', value: '100' },
    ]);
  });

  test('drops facts it has no value for', () => {
    expect(detailFacts({ id: 1 })).toEqual([]);
  });

  test('blank equipment entries do not use up the four slots', () => {
    expect(detailFacts({ equipment: ['', '  ', 'Tent', 'RV', 'Van', 'Boat', 'Trailer'] })).toEqual([
      { label: 'Equipment', value: 'Tent, RV, Van, Boat' },
    ]);
  });

  test('falls back to the backend label when the provider listed no words of its own', () => {
    expect(detailFacts({ kind: 'rv', kind_label: 'RV' })).toEqual([{ label: 'Type', value: 'RV' }]);
  });
});

describe('capacity', () => {
  test('capacity phrasing', () => {
    expect(capacityLabel({ min_people: 2, max_people: 6 })).toBe('2-6 people');
    expect(capacityLabel({ max_people: 6 })).toBe('Up to 6 people');
    expect(capacityLabel({ min_people: 2 })).toBe('2+ people');
    expect(capacityLabel({ min_people: 4, max_people: 4 })).toBe('Up to 4 people');
    expect(capacityLabel({})).toBe('');
  });

  test('the range is the shared decision, not the prose', () => {
    expect(capacityRange({ min_people: 2, max_people: 6 })).toEqual({ min: 2, max: 6 });
    expect(capacityRange({ max_people: 6 })).toEqual({ min: null, max: 6 });
    expect(capacityRange({})).toBeNull();
  });
});

describe('feature chips', () => {
  test('columns, measurements, then attributes; false is not a chip', () => {
    expect(featureLabels(site)).toEqual([
      'Firepit',
      'Max RV length: 32 ft',
      'Reserve type: Site-Specific',
      'Pets allowed',
      'Shade: Partial',
    ]);
  });

  test('format the measurements with units', () => {
    expect(featureLabels({ max_cars: 2, driveway_length: 40, max_trailer_length: 18 })).toEqual([
      'Max cars: 2',
      'Driveway length: 40 ft',
      'Max trailer length: 18 ft',
    ]);
  });

  test('deduplicate a fact that arrives as a column and an attribute', () => {
    expect(featureLabels({ firepit: true, attributes: [{ name: 'firepit' }] })).toEqual(['Firepit']);
  });

  test('a blank attribute name is not a chip', () => {
    expect(featureLabels({ attributes: [{ name: '' }, { name: '  ' }, { name: 'Shade' }] })).toEqual(
      ['Shade'],
    );
  });

  test('a blank attribute name is not a chip even when it has a value', () => {
    expect(
      featureLabels({
        attributes: [
          { name: '', value: 'Yes' },
          { name: '  ', value: 'No' },
          { name: 'Shade', value: 'Partial' },
        ],
      }),
    ).toEqual(['Shade: Partial']);
  });

  test('truncate a chip whose attribute value runs on', () => {
    const [chip] = featureLabels({ attributes: [{ name: 'Notes', value: 'x'.repeat(200) }] });

    expect(chip).toHaveLength(84);
    expect(chip.endsWith('...')).toBe(true);
  });

  test('are capped so a dense row stays readable', () => {
    const many = Array.from({ length: 40 }, (_, index) => ({ name: `Feature ${index}` }));

    expect(featureLabels({ attributes: many })).toHaveLength(12);
  });
});

describe('the description', () => {
  test('collapses whitespace and clamps to 260 chars', () => {
    expect(descriptionText('Waterfront   site.')).toBe('Waterfront site.');
    expect(descriptionText('x'.repeat(400))).toHaveLength(260);
    expect(descriptionText('x'.repeat(400)).endsWith('...')).toBe(true);
  });

  test('is empty for nothing useful', () => {
    expect(descriptionText(null)).toBe('');
    expect(descriptionText('   ')).toBe('');
  });
});
