// Row content for the selected-day site list.
import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import {
  campsitesForIds,
  compareListRows,
  descriptionSummary,
  rowDetails,
  siteListLabel,
} from './site-list-rows';

const site = (id: number, extra: Partial<Campsite> = {}) =>
  ({ id, name: `Site ${id}`, ...extra }) as Campsite;

describe('resolving a day"s ids to rows', () => {
  test('keeps the id list"s order', () => {
    const rows = campsitesForIds([site(1), site(2), site(3)], ['3', '1']);

    expect(rows.map((row) => row.id)).toEqual([3, 1]);
  });

  // The header's count comes from the availability response. Dropping a row the
  // catalog is missing would make that count look wrong.
  test('stubs a row the catalog does not have', () => {
    const rows = campsitesForIds([site(1)], ['1', '99']);

    expect(rows).toHaveLength(2);
    expect(rows[1]).toMatchObject({ id: '99', data_provider_ref: '99' });
  });

  test('tolerates no catalog at all', () => {
    expect(campsitesForIds(null, ['5'])).toHaveLength(1);
  });

  test('matches a numeric catalog id against a string id', () => {
    expect(campsitesForIds([site(7)], ['7'])[0]!.name).toBe('Site 7');
  });
});

describe('the row summary', () => {
  test('combines capacity and description', () => {
    expect(
      rowDetails(site(1, { max_people: 6, source_payload: { description: 'By the water.' } })),
    ).toEqual(['Sleeps up to 6', 'By the water.']);
  });

  // Three different claims, phrased differently on purpose.
  test('phrases capacity by what is known', () => {
    expect(rowDetails(site(1, { source_payload: { min_capacity: 2, max_capacity: 6 } }))).toEqual([
      'Sleeps 2-6',
    ]);
    expect(rowDetails(site(1, { source_payload: { min_capacity: 2 } }))).toEqual(['Sleeps 2+']);
  });

  test('is empty when the row says nothing', () => {
    expect(rowDetails(site(1))).toEqual([]);
  });

  test('ignores a non-object payload', () => {
    expect(rowDetails(site(1, { source_payload: 'nope' }))).toEqual([]);
  });
});

describe('the description summary', () => {
  test('flattens markup and whitespace', () => {
    expect(descriptionSummary('<p>Walk-in   site.</p>\n<p>Shaded.</p>')).toBe(
      'Walk-in site. Shaded.',
    );
  });

  test('clamps to two lines" worth', () => {
    const summary = descriptionSummary('x'.repeat(200));

    expect(summary).toHaveLength(120);
    expect(summary.endsWith('...')).toBe(true);
  });

  test('is empty for nothing', () => {
    expect(descriptionSummary(null)).toBe('');
    expect(descriptionSummary('<p>  </p>')).toBe('');
  });
});

describe('the header label', () => {
  test('says how much of the campground is open', () => {
    expect(siteListLabel(3, 240)).toBe('Available sites (3 of 240 sites)');
  });

  test('omits a total it does not have', () => {
    expect(siteListLabel(3, null)).toBe('Available sites (3)');
  });

  test('omits the count it does not have', () => {
    expect(siteListLabel(null, 240)).toBe('Available sites');
  });
});

describe('row order', () => {
  test('is loop, then site, numerically', () => {
    const rows = [
      site(1, { name: 'Site 10', loop_name: 'B' }),
      site(2, { name: 'Site 9', loop_name: 'B' }),
      site(3, { name: 'Site 1', loop_name: 'A' }),
    ].sort(compareListRows);

    expect(rows.map((row) => row.name)).toEqual(['Site 1', 'Site 9', 'Site 10']);
  });

  // Aspira's resource-id-only rows are the least identifiable, so they go last.
  test('puts loop-less rows last', () => {
    const rows = [site(1, { name: 'Zed' }), site(2, { name: 'Aaa', loop_name: 'B' })].sort(
      compareListRows,
    );

    expect(rows.map((row) => row.name)).toEqual(['Aaa', 'Zed']);
  });
});
