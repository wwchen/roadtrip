import { expect, test } from 'vitest';
import { carrierLabel, carriers } from './carrier.js';

const KOTLIN_CARRIERS: Record<string, string> = {
  verizon: 'Verizon',
  att: 'AT&T',
  tmobile: 'T-Mobile',
  sprint: 'Sprint',
  uscell: 'US Cellular',
};

test('members match the Kotlin enum exactly', () => {
  expect([...carriers]).toEqual(Object.keys(KOTLIN_CARRIERS));
});

test('every Carrier has the Kotlin label', () => {
  for (const c of carriers) {
    expect(carrierLabel(c)).toBe(KOTLIN_CARRIERS[c]);
  }
});

test('the member array is frozen', () => {
  expect(Object.isFrozen(carriers)).toBe(true);
});
