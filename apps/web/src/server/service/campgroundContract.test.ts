import { expect, test } from 'vitest';
import { amenityDtos, bookingSiteOf, carrierSignalDto, ratingDto } from './campgroundContract';

test('an absent amenity with a negative label reads as its negative; one without is dropped', () => {
  expect(amenityDtos([{ key: 'showers', present: false }, { key: 'camp_store', present: false }])).toEqual([
    { key: 'showers', label: 'No showers', present: false },
  ]);
});

test('toilets with a detail read "<Detail> toilets"; other reads its detail; detail is trimmed', () => {
  expect(amenityDtos([{ key: 'toilets', detail: ' vault ' }, { key: 'other', detail: 'Boat launch' }, { key: 'water', detail: 'seasonal' }])).toEqual([
    { key: 'toilets', label: 'Vault toilets', present: true, detail: 'vault' },
    { key: 'other', label: 'Boat launch', present: true, detail: 'Boat launch' },
    { key: 'water', label: 'Water: seasonal', present: true, detail: 'seasonal' },
  ]);
});

test('a carrier signal carries its display label', () => {
  expect(carrierSignalDto({ carrier: 'att', average: 2.5 })).toEqual({ carrier: 'att', label: 'AT&T', average: 2.5 });
});

test('a rating over no reviews is not served', () => {
  expect(ratingDto({ average: 4, count: 0 })).toBeUndefined();
  expect(ratingDto({ average: 4, count: 3 })).toEqual({ average: 4, count: 3 });
});

test('the booking site is the lowercased host without www or port', () => {
  expect(bookingSiteOf('https://www.Example.org:443/book?x=1')).toBe('example.org');
  expect(bookingSiteOf('not a url')).toBeUndefined();
});
