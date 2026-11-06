import { expect, test } from 'vitest';
import { bookingIdentities } from './booking-identities.js';

test('the primary ref comes first, then each alias in stored order', () => {
  const identities = bookingIdentities('aspira', 'bc:1:2:null', [
    { provider: 'campflare', ref: 'mahood' },
    { provider: 'recgov', ref: '232450' },
  ]);
  expect(identities.map((ref) => [ref.provider, ref.serialize()])).toEqual([
    ['aspira', 'bc:1:2:null'],
    ['campflare', 'mahood'],
    ['recgov', '232450'],
  ]);
});

test('blank refs, unknown providers and unparseable refs name nothing', () => {
  expect(bookingIdentities('aspira', '  ', [{ provider: 'campflare', ref: '' }])).toEqual([]);
  expect(bookingIdentities('nobody', 'x', [])).toEqual([]);
  expect(bookingIdentities(null, null, [{ provider: 'aspira', ref: 'not-an-aspira-ref' }])).toEqual([]);
});
