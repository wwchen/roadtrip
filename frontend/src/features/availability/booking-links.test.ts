import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import {
  agencyLabel,
  bookingLabel,
  hasReservationUrlTemplate,
  providerLabel,
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

describe('naming who takes the booking', () => {
  test('recognises the hosts we integrate with', () => {
    const cases: Array<[string, string]> = [
      ['https://www.recreation.gov/x', 'Recreation.gov'],
      ['https://recreation.gov/x', 'Recreation.gov'],
      ['https://reservation.pc.gc.ca/x', 'Parks Canada'],
      ['https://camping.bcparks.ca/x', 'BC Parks'],
      ['https://discovercamping.ca/x', 'BC Parks'],
      ['https://washington.goingtocamp.com/x', 'Washington State Parks'],
      ['https://campflare.com/x', 'Campflare'],
      ['https://shop.albertaparks.ca/x', 'Alberta Parks'],
      ['https://newyorkstateparks.reserveamerica.com/x', 'New York State Parks'],
      ['https://www.reservecalifornia.com/x', 'ReserveCalifornia'],
      ['https://reservecalifornia.com/x', 'ReserveCalifornia'],
    ];
    for (const [url, expected] of cases) {
      expect(agencyLabel(site(7), { 7: url })).toBe(expected);
    }
  });

  test('falls back to the row"s vendor when the template is unparseable', () => {
    expect(agencyLabel(site(7, { data_provider: 'aspira' }), { 7: 'not a url' })).toBe('Aspira');
    expect(agencyLabel(site(7, { data_provider: 'recgov' }), {})).toBe('Recreation.gov');
  });

  test('humanises an unknown host', () => {
    expect(agencyLabel(site(7), { 7: 'https://www.camp-oregon.gov/x' })).toBe('Camp Oregon');
  });

  test('a named host outranks the row"s vendor slug', () => {
    // The link opens a ReserveAmerica-family site; the row is a Campflare
    // catalog row. Naming the row would label the button after a site it does
    // not open.
    const row = site(7, { booking_provider: 'campflare', data_provider: 'campflare' });
    const url = 'https://newyorkstateparks.reserveamerica.com/x';

    expect(agencyLabel(row, { 7: url })).toBe('New York State Parks');
    expect(agencyLabel(row, { 7: url })).not.toBe('Campflare');
  });

  test('a vendor we know beats a word guessed off an unmapped host', () => {
    // `shop.albertaparks.ca` used to read as "Shop"; a host we have no row for
    // is a worse name than the vendor the row declares.
    const row = site(7, { booking_provider: 'reserveamerica', data_provider: 'campflare' });

    expect(agencyLabel(row, { 7: 'https://shop.example.ca/x' })).toBe('ReserveAmerica');
    expect(agencyLabel(site(7, { booking_provider: 'reservecalifornia' }), {})).toBe(
      'ReserveCalifornia',
    );
  });

  test('humanises an unknown vendor slug', () => {
    expect(agencyLabel(site(7, { data_provider: 'some_vendor' }), {})).toBe('Some Vendor');
  });

  test('names a provider id straight from the wire, humanising an unknown one', () => {
    expect(providerLabel('recgov')).toBe('Recreation.gov');
    expect(providerLabel('campflare')).toBe('Campflare');
    expect(providerLabel('some_vendor')).toBe('Some Vendor');
    expect(providerLabel(undefined)).toBe('');
  });

  test('labels the button, and degrades to plain Book', () => {
    expect(bookingLabel(site(7), { 7: 'https://www.recreation.gov/x' })).toBe(
      'Book on Recreation.gov',
    );
    expect(bookingLabel(site(7), {})).toBe('Book');
  });
});
