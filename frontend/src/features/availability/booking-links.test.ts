import { describe, expect, it, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import {
  bookingLabel,
  hasReservationUrlTemplate,
  reservationUrlFromTemplate,
} from './booking-links';

const site = (id: number, extra: Partial<Campsite> = {}): Partial<Campsite> => ({ id, ...extra });

const RECGOV = 'https://www.recreation.gov/camping/campsites/7?start={start_date}&end={end_date}&nights={nights}';

describe('building the URL', () => {
  test('substitutes the whole window', () => {
    expect(
      reservationUrlFromTemplate(site(7), {
        startDate: '2026-08-11',
        endDate: '2026-08-13',
        reservationUrlTemplates: { 7: RECGOV },
      }),
    ).toBe(
      'https://www.recreation.gov/camping/campsites/7?start=2026-08-11&end=2026-08-13&nights=2',
    );
  });

  test('a template with no placeholders is already a URL', () => {
    expect(
      reservationUrlFromTemplate(site(7), {
        reservationUrlTemplates: { 7: 'https://parks.example/site/7' },
      }),
    ).toBe('https://parks.example/site/7');
  });

  test('refuses to fill a dated template without dates', () => {
    expect(reservationUrlFromTemplate(site(7), { reservationUrlTemplates: { 7: RECGOV } })).toBe('');
    expect(
      reservationUrlFromTemplate(site(7), {
        startDate: '2026-08-11',
        reservationUrlTemplates: { 7: RECGOV },
      }),
    ).toBe('');
  });

  test('refuses a zero- or negative-night window', () => {
    const window = (startDate: string, endDate: string) =>
      reservationUrlFromTemplate(site(7), {
        startDate,
        endDate,
        reservationUrlTemplates: { 7: RECGOV },
      });

    expect(window('2026-08-11', '2026-08-11')).toBe('');
    expect(window('2026-08-13', '2026-08-11')).toBe('');
  });

  test('counts nights across a DST transition', () => {
    expect(
      reservationUrlFromTemplate(site(7), {
        startDate: '2026-03-07',
        endDate: '2026-03-09',
        reservationUrlTemplates: { 7: RECGOV },
      }),
    ).toContain('nights=2');
  });

  test('no template for this row means no URL', () => {
    expect(reservationUrlFromTemplate(site(7), { reservationUrlTemplates: { 9: RECGOV } })).toBe('');
    expect(reservationUrlFromTemplate(site(7), {})).toBe('');
    expect(reservationUrlFromTemplate(null, { reservationUrlTemplates: { 7: RECGOV } })).toBe('');
  });

  test('accepts a Map as well as an object', () => {
    expect(
      reservationUrlFromTemplate(site(7), {
        reservationUrlTemplates: new Map([['7', 'https://parks.example/7']]),
      }),
    ).toBe('https://parks.example/7');
  });

  test('does not resolve inherited object members', () => {
    expect(
      reservationUrlFromTemplate({ id: 'constructor' as unknown as number }, {
        reservationUrlTemplates: {},
      }),
    ).toBe('');
  });

  test('trims a padded template', () => {
    expect(
      reservationUrlFromTemplate(site(7), {
        reservationUrlTemplates: { 7: '  https://parks.example/7  ' },
      }),
    ).toBe('https://parks.example/7');
  });
});

describe('whether a row could ever be booked', () => {
  test('is true for a dated template with no dates supplied', () => {
    expect(hasReservationUrlTemplate(site(7), { 7: RECGOV })).toBe(true);
    expect(reservationUrlFromTemplate(site(7), { reservationUrlTemplates: { 7: RECGOV } })).toBe('');
  });

  test('is false with no template', () => {
    expect(hasReservationUrlTemplate(site(7), {})).toBe(false);
    expect(hasReservationUrlTemplate(site(7), null)).toBe(false);
    // Whitespace is not a template.
    expect(hasReservationUrlTemplate(site(7), { 7: '   ' })).toBe(false);
  });
});

describe('bookingLabel', () => {
  it('names the site the backend served', () => {
    expect(bookingLabel({ id: 1, booking_system: 'BC Parks' })).toBe('Book on BC Parks');
  });

  it('falls back to the neutral verb when nothing was served', () => {
    expect(bookingLabel({ id: 1 })).toBe('Book');
    expect(bookingLabel({ id: 1, booking_system: '' })).toBe('Book');
    expect(bookingLabel(null)).toBe('Book');
  });
});
