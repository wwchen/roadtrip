import { expect, test } from 'vitest';
import { Carrier, carrierFromVendorName, carrierLabel, carriers } from './carrier.js';

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

const NO_BREAK_SPACE = String.fromCodePoint(0xa0);
const FILE_SEPARATOR = String.fromCodePoint(0x1c);
const BYTE_ORDER_MARK = String.fromCodePoint(0xfeff);

test.each([
  ['Verizon', Carrier.VERIZON],
  ['AT&T', Carrier.ATT],
  ['T-Mobile', Carrier.TMOBILE],
  ['Sprint', Carrier.SPRINT],
  ['US Cellular', Carrier.US_CELLULAR],
  ['verizon', Carrier.VERIZON],
  ['AT&t', Carrier.ATT],
  ['  us cellular ', Carrier.US_CELLULAR],
  [`${NO_BREAK_SPACE}Sprint${FILE_SEPARATOR}`, Carrier.SPRINT],
])('the vendor name %j is read as %s', (name, expected) => {
  expect(carrierFromVendorName(name)).toBe(expected);
});

test.each(['att', 'uscell', 'tmobile', 'Boost Mobile', '', ' ', 'US  Cellular', 'T Mobile', `${BYTE_ORDER_MARK}Verizon`, 'Verizon Wireless'])(
  'the name %j is outside the vendor vocabulary',
  (name) => {
    expect(carrierFromVendorName(name)).toBeNull();
  },
);
