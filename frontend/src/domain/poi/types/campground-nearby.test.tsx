// The neighbours block's seam.
//
// There is no proximity source yet — no endpoint answers "n closest to this pin" —
// so the contract these tests pin is the shape of the hole: the block appears when
// and only when a surface hands the type a non-empty list, and today no surface
// does. See `nearby` in `types/common.tsx` for what is missing.
import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import type { PoiNeighbour } from '../model';
import { CampgroundPoiPage } from './campground';

const campground = (): FlatPoiFeature => ({
  type: 'Feature',
  id: 7,
  geometry: { type: 'Point', coordinates: [-122.9, 43.9] },
  properties: { category: 'campground', name: 'Jasper State Recreation Site' },
});

const neighbours: PoiNeighbour[] = [
  { id: '1', name: 'Elijah Bristow', meta: '2 mi · State park', status: 'Day use only' },
  { id: '2', name: 'Fall Creek', meta: '12 mi · State park', href: '/poi?poi=2' },
];

const carousel = () => screen.queryByRole('list', { name: 'Campgrounds nearby' });

describe('the campground page’s nearby block', () => {
  test('is absent when nothing supplies neighbours — which is every real record today', () => {
    render(<CampgroundPoiPage variant="page" feature={campground()} />);

    expect(carousel()).toBeNull();
    expect(screen.queryByText(/closest/)).toBeNull();
  });

  // An empty list is the same answer as no list: a heading over "0 closest" promises
  // neighbours the page does not have.
  test('is absent when the injected list is empty', () => {
    render(<CampgroundPoiPage variant="page" feature={campground()} nearby={[]} />);

    expect(carousel()).toBeNull();
  });

  test('renders the injected neighbours, count and all', () => {
    render(<CampgroundPoiPage variant="page" feature={campground()} nearby={neighbours} />);

    expect(carousel()).not.toBeNull();
    expect(screen.getByText('2 closest · scroll')).toBeInTheDocument();
    expect(screen.getByText('Elijah Bristow')).toBeInTheDocument();
    expect(screen.getByText('Day use only')).toBeInTheDocument();
    // A neighbour with a page is a link; one without is text, exactly as the block
    // renders any other list.
    expect(screen.getByRole('link', { name: 'Fall Creek' })).toHaveAttribute('href', '/poi?poi=2');
    expect(screen.queryByRole('link', { name: 'Elijah Bristow' })).toBeNull();
  });

  // The overflow is the whole point of a scroller, and a keyboard reaches it only
  // through a tab stop.
  test('the scroller is focusable so a keyboard can reach the overflow', () => {
    render(<CampgroundPoiPage variant="page" feature={campground()} nearby={neighbours} />);

    expect(carousel()).toHaveAttribute('tabindex', '0');
  });
});
