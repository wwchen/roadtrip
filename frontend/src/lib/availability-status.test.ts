import { describe, expect, test } from 'vitest';
import {
  availabilityStatusAria,
  availabilityStatusLabel,
  availabilityStatusMeta,
  normalizeAvailabilityStatus,
} from './availability-status';

describe('normalizeAvailabilityStatus', () => {
  test.each([
    ['available', 'available'],
    ['first_come', 'first_come'],
    ['reserved', 'reserved'],
    ['closed', 'closed'],
    ['unknown', 'unknown'],
    ['past', 'past'],
  ])('passes the wire value %s through', (input, expected) => {
    expect(normalizeAvailabilityStatus(input)).toBe(expected);
  });

  test('lowercases before matching', () => {
    expect(normalizeAvailabilityStatus('AVAILABLE')).toBe('available');
    expect(normalizeAvailabilityStatus('First_Come')).toBe('first_come');
  });

  test.each([['booked'], [''], ['first-come']])(
    'falls back to unknown for the unrecognised value %j',
    (input) => {
      expect(normalizeAvailabilityStatus(input)).toBe('unknown');
    },
  );

  test('falls back to unknown for null, undefined, and non-strings', () => {
    expect(normalizeAvailabilityStatus(null)).toBe('unknown');
    expect(normalizeAvailabilityStatus(undefined)).toBe('unknown');
    expect(normalizeAvailabilityStatus(0)).toBe('unknown');
  });

  // Guards against a prototype key resolving as a status.
  test('does not treat inherited object keys as statuses', () => {
    expect(normalizeAvailabilityStatus('constructor')).toBe('unknown');
    expect(normalizeAvailabilityStatus('toString')).toBe('unknown');
  });
});

describe('availabilityStatusMeta', () => {
  test('hyphenates the CSS kind for first_come', () => {
    expect(availabilityStatusMeta('first_come').kind).toBe('first-come');
  });

  test('exposes the full display record', () => {
    expect(availabilityStatusMeta('available')).toEqual({
      value: 'available',
      kind: 'available',
      label: 'A',
      aria: 'available',
      text: 'Available',
      detailClass: 'cg-status-ok',
    });
  });

  test('is frozen so a caller cannot mutate the shared record', () => {
    expect(Object.isFrozen(availabilityStatusMeta('reserved'))).toBe(true);
  });
});

describe('label and aria shorthands', () => {
  test.each([
    ['available', 'A', 'available'],
    ['first_come', 'FF', 'first come first served'],
    ['reserved', 'R', 'reserved'],
    ['closed', 'C', 'closed'],
    ['unknown', '?', 'unknown'],
    ['past', '·', 'past'],
  ])('%s renders as %s / %s', (status, label, aria) => {
    expect(availabilityStatusLabel(status)).toBe(label);
    expect(availabilityStatusAria(status)).toBe(aria);
  });

  test('an unrecognised status renders the unknown glyph', () => {
    expect(availabilityStatusLabel('bogus')).toBe('?');
  });
});
