import { expect, test } from 'vitest';
import { campsiteKindLabel, campsiteKinds } from './campsite.js';

const KOTLIN_CAMPSITE_KINDS: Record<string, string> = {
  standard: 'Standard',
  tent: 'Tent',
  rv: 'RV',
  cabin: 'Cabin',
  group: 'Group',
  walk_in: 'Walk-in',
  boat_in: 'Boat-in',
  equestrian: 'Equestrian',
  backcountry: 'Backcountry',
  day_use: 'Day use',
  other: 'Other',
};

test('members match the Kotlin enum exactly', () => {
  expect([...campsiteKinds]).toEqual(Object.keys(KOTLIN_CAMPSITE_KINDS));
});

test('every CampsiteKind has the Kotlin label', () => {
  for (const k of campsiteKinds) {
    expect(campsiteKindLabel(k)).toBe(KOTLIN_CAMPSITE_KINDS[k]);
  }
});

test('the member array is frozen', () => {
  expect(Object.isFrozen(campsiteKinds)).toBe(true);
});
