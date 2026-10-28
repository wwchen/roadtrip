import { expect, test } from 'vitest';
import { amenityKeys, amenityLabel, amenityNegativeLabel } from './amenity.js';

const KOTLIN_AMENITY_KEYS = [
  'camp_store',
  'dump_station',
  'electric_hookups',
  'fires_allowed',
  'pets_allowed',
  'sewer_hookups',
  'showers',
  'toilets',
  'trash',
  'water',
  'water_hookups',
  'wifi',
  'other',
];

const KOTLIN_AMENITY_LABELS: Record<string, string> = {
  camp_store: 'Camp store',
  dump_station: 'Dump station',
  electric_hookups: 'Electric hookups',
  fires_allowed: 'Fires allowed',
  pets_allowed: 'Pets allowed',
  sewer_hookups: 'Sewer hookups',
  showers: 'Showers',
  toilets: 'Toilets',
  trash: 'Trash',
  water: 'Water',
  water_hookups: 'Water hookups',
  wifi: 'Wi-Fi',
  other: 'Other',
};

const KOTLIN_AMENITY_NEGATIVE_LABELS: Record<string, string> = {
  electric_hookups: 'No electric hookups',
  sewer_hookups: 'No sewer hookups',
  showers: 'No showers',
  toilets: 'No toilets',
  water: 'No water',
  water_hookups: 'No water hookups',
};

test('members match the Kotlin enum exactly', () => {
  expect([...amenityKeys].sort()).toEqual([...KOTLIN_AMENITY_KEYS].sort());
});

test('members keep the Kotlin declaration order', () => {
  expect([...amenityKeys]).toEqual(KOTLIN_AMENITY_KEYS);
});

test('every AmenityKey has the Kotlin label', () => {
  for (const k of amenityKeys) {
    expect(amenityLabel(k)).toBe(KOTLIN_AMENITY_LABELS[k]);
  }
});

test('negative labels exist for exactly the keys Kotlin gives one', () => {
  for (const k of amenityKeys) {
    expect(amenityNegativeLabel(k)).toBe(KOTLIN_AMENITY_NEGATIVE_LABELS[k]);
  }
});

test('the member array is frozen', () => {
  expect(Object.isFrozen(amenityKeys)).toBe(true);
});
