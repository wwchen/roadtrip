// The dashboard's URL contract. These shapes are linked to from poller rows and
// rewritten as filters change, so they are a published interface, not internals.
import { describe, expect, test } from 'vitest';
import { readTabRoute, tabSearch, TAB_CHANGES, TAB_POLLERS, TAB_RUNS } from './useTabRoute';

describe('readTabRoute', () => {
  test('reads the named tab', () => {
    expect(readTabRoute('?tab=runs').tab).toBe(TAB_RUNS);
    expect(readTabRoute('?tab=changes').tab).toBe(TAB_CHANGES);
  });

  test('defaults to pollers when no tab is named', () => {
    expect(readTabRoute('').tab).toBe(TAB_POLLERS);
    expect(readTabRoute('?poller_id=7').tab).toBe(TAB_POLLERS);
  });

  // An unknown tab falls back rather than rendering nothing, matching the legacy
  // `TABS[tab] ? tab : 'pollers'` guard.
  test('falls back to pollers for an unknown tab', () => {
    expect(readTabRoute('?tab=nope').tab).toBe(TAB_POLLERS);
  });

  test('passes every other param through', () => {
    expect(readTabRoute('?tab=runs&poller_id=7&status=failed').params).toEqual({
      poller_id: '7',
      status: 'failed',
    });
  });

  test('does not leak tab into the params', () => {
    expect(readTabRoute('?tab=runs').params).toEqual({});
  });
});

describe('tabSearch', () => {
  test('always names the tab', () => {
    expect(tabSearch(TAB_POLLERS)).toBe('?tab=pollers');
  });

  test('includes params', () => {
    expect(tabSearch(TAB_RUNS, { poller_id: '7' })).toBe('?tab=runs&poller_id=7');
  });

  // Kept from the original: an empty filter is absent from the URL rather than
  // present and blank, so a reset link is clean.
  test('drops empty values', () => {
    expect(tabSearch(TAB_RUNS, { poller_id: '', status: 'failed' })).toBe(
      '?tab=runs&status=failed',
    );
  });

  test('escapes values', () => {
    expect(tabSearch(TAB_CHANGES, { poi_id: 'a b&c' })).toBe('?tab=changes&poi_id=a+b%26c');
  });
});
