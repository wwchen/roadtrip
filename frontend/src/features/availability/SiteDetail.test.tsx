// The one "Book on <name>" that sits directly on an `href`.
//
// These assert the *pair*: the anchor's accessible name against the URL it
// opens. A name served by one resolver on an href built by another is the
// failure this file exists to catch, so neither is checked alone.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import { SiteDetail } from './SiteDetail';

const SITE_ID = 7;

const RECGOV_TEMPLATE =
  'https://www.recreation.gov/camping/campsites/7?startDate={start_date}&endDate={end_date}';

const site = (extra: Partial<Campsite> = {}): Partial<Campsite> => ({
  id: SITE_ID,
  name: 'Site 12',
  ...extra,
});

function renderDetail(row: Partial<Campsite>, templates: Record<number, string> | null) {
  render(
    <SiteDetail
      site={row}
      selectedDate="2026-08-11"
      selectedEndDate="2026-08-13"
      reservationUrlTemplates={templates}
    />,
  );
  return screen.queryByRole('link');
}

describe('the site-detail booking anchor', () => {
  test('names the served booking site and opens that row template', () => {
    const link = renderDetail(site({ booking_system: 'BC Parks' }), { [SITE_ID]: RECGOV_TEMPLATE });

    expect(link).toHaveAccessibleName('Book on BC Parks');
    expect(link).toHaveAttribute(
      'href',
      'https://www.recreation.gov/camping/campsites/7?startDate=2026-08-11&endDate=2026-08-13',
    );
  });

  test('falls back to the bare verb when the row names no booking site', () => {
    const link = renderDetail(site(), { [SITE_ID]: RECGOV_TEMPLATE });

    // Not "Book on " and not a guess off the href's host: the frontend renders
    // the name it was served or no name at all.
    expect(link).toHaveAccessibleName('Book');
    expect(link).toHaveAttribute(
      'href',
      'https://www.recreation.gov/camping/campsites/7?startDate=2026-08-11&endDate=2026-08-13',
    );
  });

  test('a blank served name is the same as none', () => {
    expect(renderDetail(site({ booking_system: '   ' }), { [SITE_ID]: RECGOV_TEMPLATE }))
      .toHaveAccessibleName('Book');
  });

  test('no template for this row means no anchor at all', () => {
    // A name with nowhere to go is worse than no button: the label would promise
    // a booking page the component cannot open.
    expect(renderDetail(site({ booking_system: 'BC Parks' }), null)).toBeNull();
    expect(renderDetail(site({ booking_system: 'BC Parks' }), { 9: RECGOV_TEMPLATE })).toBeNull();
  });
});
