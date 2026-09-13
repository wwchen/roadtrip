import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import { inViewCopy } from '@/lib/strings';
import { siteCountsOf } from './site-counts';

const site = (id: number): Campsite => ({
  id,
  campground_id: 11,
  name: `Site ${id}`,
  kind: 'tent',
  kind_label: 'Tent',
  equipment: [],
  attributes: [],
  data_provider: 'recgov',
  data_provider_ref: String(id),
});

describe('siteCountsOf', () => {
  test('counts the catalog', () => {
    expect(siteCountsOf([site(1), site(2), site(3)])).toEqual({ total: 3 });
  });

  test('an absent catalog counts as none', () => {
    expect(siteCountsOf(undefined)).toEqual({ total: 0 });
    expect(siteCountsOf(null)).toEqual({ total: 0 });
  });
});

describe('inViewCopy.sites', () => {
  test('pluralises', () => {
    expect(inViewCopy.sites(1)).toBe('1 site');
    expect(inViewCopy.sites(54)).toBe('54 sites');
    expect(inViewCopy.sites(0)).toBe('0 sites');
  });
});
