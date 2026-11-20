import { describe, expect, test } from 'vitest';
import { createColumnDecoder } from '../../repo/columnDecoder';
import { bookingIdentityOf } from './bookingIdentityRow';

const ALIAS = { provider: 'recgov', ref: '232447' };
const CAMPGROUND_PREFIX = 'cg_';

describe('bookingIdentityOf', () => {
  test('reads the three columns', () => {
    const decoder = createColumnDecoder('test row');
    const identity = bookingIdentityOf(
      { booking_provider: 'recgov', booking_provider_ref: '100', booking_aliases: [ALIAS] },
      decoder,
    );
    expect(identity).toEqual({ bookingProvider: 'recgov', bookingProviderRef: '100', bookingAliases: [ALIAS] });
    expect(decoder.decodeErrors).toEqual([]);
  });

  test('reads them under a prefix', () => {
    const decoder = createColumnDecoder('test row');
    const identity = bookingIdentityOf(
      {
        booking_provider: 'other',
        cg_booking_provider: 'recgov',
        cg_booking_provider_ref: '200',
        cg_booking_aliases: [ALIAS],
      },
      decoder,
      CAMPGROUND_PREFIX,
    );
    expect(identity).toEqual({ bookingProvider: 'recgov', bookingProviderRef: '200', bookingAliases: [ALIAS] });
  });

  test('missing or malformed aliases read as none', () => {
    const missing = createColumnDecoder('test row');
    expect(bookingIdentityOf({ booking_provider: null, booking_provider_ref: null }, missing).bookingAliases).toEqual([]);
    const malformed = createColumnDecoder('test row');
    expect(
      bookingIdentityOf({ booking_provider: 'recgov', booking_provider_ref: '1', booking_aliases: 'not aliases' }, malformed).bookingAliases,
    ).toEqual([]);
    expect(malformed.decodeErrors).toHaveLength(1);
  });
});
