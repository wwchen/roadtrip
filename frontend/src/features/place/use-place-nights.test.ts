import { describe, expect, test } from 'vitest';
import { nightsOf, siteLabel } from './use-place-nights';

const START = '2026-10-25';

describe('nightsOf', () => {
  test('names every night of the stay and not the morning you leave', () => {
    expect(nightsOf(START, '2026-10-28')).toEqual(['2026-10-25', '2026-10-26', '2026-10-27']);
  });

  test('a window that ends where it starts has no nights in it', () => {
    expect(nightsOf(START, START)).toEqual([]);
  });

  test('a window longer than the strip can show is cut, not looped forever', () => {
    expect(nightsOf(START, '2027-10-25').length).toBe(30);
  });
});

describe('siteLabel', () => {
  test('a named site in a loop reads as both', () => {
    expect(siteLabel({ name: '12', loop_name: 'Loop A' })).toBe('Loop A / 12');
  });

  test('a named site with no loop is just its name', () => {
    expect(siteLabel({ name: 'Walk-in 3' })).toBe('Walk-in 3');
  });

  test('a site the provider only numbered reads as the number on the post', () => {
    expect(siteLabel({ data_provider_ref: '4711' })).toBe('Site #4711');
  });

  test('a site with nothing to call it is not named at all', () => {
    expect(siteLabel({})).toBe('');
  });
});
