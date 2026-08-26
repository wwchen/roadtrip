import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { ParkGroupPoiPage } from './group';
import { poiPageFor } from './registry';

const group = (properties: Record<string, unknown>): FlatPoiFeature => ({
  type: 'Feature',
  id: 1,
  geometry: { type: 'Point', coordinates: [-119.5933, 37.7449] },
  properties,
});

const PROPERTIES = {
  category: 'park-group',
  name: 'Yosemite National Park',
  State_Nm: 'California',
  Mang_Name: 'National Park Service',
  nights: [
    { date: '2026-07-03', label: 'Fri', sublabel: 'Jul 3' },
    { date: '2026-07-04', label: 'Sat', sublabel: 'Jul 4' },
  ],
  campgrounds: [
    {
      id: 'upper-pines',
      name: 'Upper Pines',
      note: 'Yosemite Valley',
      nights: { '2026-07-03': { status: 'available', open: 6 }, '2026-07-04': 'reserved' },
    },
    {
      id: 'tuolumne',
      name: 'Tuolumne Meadows',
      nights: { '2026-07-03': 'first_come', '2026-07-04': 'nonsense' },
    },
  ],
};

describe('the park group page', () => {
  test('the registry routes the group category here', () => {
    expect(poiPageFor(PROPERTIES)).toBe(ParkGroupPoiPage);
  });

  test('the step above is the park system, and the subtitle keeps the state', () => {
    render(<ParkGroupPoiPage variant="page" feature={group(PROPERTIES)} />);

    expect(screen.getByLabelText('Breadcrumb').textContent).toBe('National parks');
    expect(screen.getByText('California · National Park Service')).toBeTruthy();
  });

  test('the campgrounds arrive as rows against the park’s nights', () => {
    render(<ParkGroupPoiPage variant="page" feature={group(PROPERTIES)} />);

    expect(screen.getByRole('rowheader', { name: /Upper Pines/ })).toBeTruthy();
    expect(screen.getByLabelText(/^Upper Pines, Fri Jul 3: available/).textContent).toBe('A6');
    expect(screen.getByLabelText(/^Tuolumne Meadows, Fri Jul 3: first come/).textContent).toBe('FF');
  });

  // A status the wire grows tomorrow must not blank the block out; it reads as
  // "we do not know", which is what it is.
  test('a status nobody recognises reads as unknown', () => {
    render(<ParkGroupPoiPage variant="page" feature={group(PROPERTIES)} />);

    expect(screen.getByLabelText(/^Tuolumne Meadows, Sat Jul 4: unknown/).textContent).toBe('?');
  });

  // Absent, not empty: the same rule the park page follows for the rollup it has
  // no endpoint for.
  test('a park with no nights omits the block rather than showing a blank one', () => {
    render(
      <ParkGroupPoiPage
        variant="page"
        feature={group({ category: 'park-group', name: 'Yosemite National Park' })}
      />,
    );

    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText('Yosemite National Park')).toBeTruthy();
  });

  // The eyebrow drops a type word the name already carries.
  test('the eyebrow does not repeat the park’s own name', () => {
    render(<ParkGroupPoiPage variant="page" feature={group(PROPERTIES)} />);

    expect(screen.queryByText('National park')).toBeNull();
  });
});
