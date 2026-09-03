import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import {
  attributeLabels,
  capacityLabel,
  descriptionText,
  detailFacts,
  featureLabels,
  photoUrl,
} from './site-detail-facts';

const site = (extra: Partial<Campsite> = {}): Partial<Campsite> => ({ id: 1, ...extra });
const facts = (s: Partial<Campsite>) => Object.fromEntries(detailFacts(s).map((f) => [f.label, f.value]));

describe('capacity', () => {
  test('reads a range, a ceiling and a floor as different claims', () => {
    expect(capacityLabel(site({ max_people: 6 }), { min_num_people: 2 })).toBe('2-6 people');
    expect(capacityLabel(site({ max_people: 6 }), {})).toBe('Up to 6 people');
    expect(capacityLabel(site(), { min_capacity: 2 })).toBe('2+ people');
  });

  test('collapses a range whose ends agree', () => {
    expect(capacityLabel(site({ max_people: 6 }), { min_capacity: 6 })).toBe('Up to 6 people');
  });

  test('says nothing when it knows nothing', () => {
    expect(capacityLabel(site(), {})).toBe('');
    expect(capacityLabel(site({ max_people: null }), { min_capacity: 'lots' })).toBe('');
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

  test('falls back to the kind when nothing more specific was listed', () => {
    expect(facts(site({ kind: 'site' }))).toEqual({ Type: 'site' });
  });

  test('lists at most four equipment types', () => {
    const equipment = ['Tent', 'RV', 'Van', 'Trailer', 'Boat'].map((name) => ({ name }));

    expect(facts(site({ equipment }))).toMatchObject({ Equipment: 'Tent, RV, Van, Trailer' });
  });
});

describe('feature chips', () => {
  test('include a true column and omit a false one', () => {
    const labels = featureLabels(site({ firepit: true, picnic_table: false }));

    expect(labels).toContain('Firepit');
    expect(labels).not.toContain('Picnic table');
  });

  test('format the measurements with units', () => {
    expect(featureLabels(site({ max_rv_length: 32, max_cars: 2 }))).toEqual([
      'Max cars: 2',
      'Max RV length: 32 ft',
    ]);
  });

  test('are capped so a dense row stays readable', () => {
    const many = Array.from({ length: 40 }, (_, index) => ({ name: `Feature ${index}` }));

    expect(featureLabels(site({ source_payload: { defined_attributes: many } }))).toHaveLength(12);
  });
});

describe('flattening the attribute list', () => {
  test('reads a name/value pair, joining a list of values', () => {
    expect(attributeLabels([{ name: 'Shade', value: 'Full' }])).toEqual(['Shade: Full']);
    expect(attributeLabels([{ name: 'Adjacent to', value: ['Lake', 'Trail'] }])).toEqual([
      'Adjacent to: Lake, Trail',
    ]);
  });

  test('keeps a name that has no value', () => {
    expect(attributeLabels([{ name: 'Pets allowed', value: null }])).toEqual(['Pets allowed']);
  });

  test('skips a definition the dictionary did not name', () => {
    expect(attributeLabels([{ definition_id: 41, value: 'x' }, 'stray'])).toEqual([]);
  });
});

describe('the description', () => {
  test('is plain text, clamped', () => {
    expect(descriptionText('<p>Waterfront   site.</p>')).toBe('Waterfront site.');
    expect(descriptionText('x'.repeat(400))).toHaveLength(260);
    expect(descriptionText('x'.repeat(400)).endsWith('...')).toBe(true);
    expect(descriptionText('x'.repeat(400), 120)).toHaveLength(120);
  });

  test('is empty for nothing useful', () => {
    expect(descriptionText(null)).toBe('');
    expect(descriptionText('   ')).toBe('');
  });
});

describe('the photo', () => {
  test('is the first one', () => {
    expect(photoUrl(site({ photos: [{ url: 'https://cdn.example/a.jpg' }, { url: 'x' }] }))).toBe(
      'https://cdn.example/a.jpg',
    );
  });

  test('is nothing when the row has none', () => {
    expect(photoUrl(site({ photos: [] }))).toBe('');
    expect(photoUrl(site())).toBe('');
  });
});
