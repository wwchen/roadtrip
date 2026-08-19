import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NightsTable, nightCells, type NightsTableNight, type NightsTablePlace } from './NightsTable';

const nights: NightsTableNight[] = [
  { date: '2026-07-03', label: 'Fri', sublabel: 'Jul 3' },
  { date: '2026-07-04', label: 'Sat', sublabel: 'Jul 4' },
  { date: '2026-07-05', label: 'Sun', sublabel: 'Jul 5' },
];

const places: NightsTablePlace[] = [
  {
    id: 'upper-pines',
    name: 'Upper Pines',
    note: 'Yosemite Valley',
    href: '?poi=upper-pines',
    cells: nightCells([
      ['2026-07-03', { status: 'available', open: 4 }],
      ['2026-07-04', { status: 'reserved' }],
      // 2026-07-05 deliberately absent.
    ]),
  },
  {
    id: 'tuolumne',
    name: 'Tuolumne Meadows',
    cells: nightCells([
      ['2026-07-03', { status: 'first_come' }],
      ['2026-07-04', { status: 'closed' }],
      ['2026-07-05', { status: 'available' }],
    ]),
  },
];

const cellFor = (place: string, night: string, state: string) =>
  screen.getByLabelText(new RegExp(`^${place}, ${night}: ${state}`));

describe('the nights table', () => {
  test('renders a row per place and a column per night', () => {
    render(<NightsTable nights={nights} places={places} />);

    // Two body rows plus the header row.
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getAllByRole('columnheader')).toHaveLength(nights.length + 1);
    expect(screen.getByRole('rowheader', { name: /Upper Pines/ })).toBeTruthy();
    expect(screen.getByRole('rowheader', { name: /Tuolumne Meadows/ })).toBeTruthy();
  });

  // The colour-blind reader is the whole reason the cell carries a glyph. If the
  // only difference between "open" and "reserved" were a background, the table
  // would be unreadable in greyscale — so the text content is asserted, not the class.
  test('every state is legible without colour', () => {
    render(<NightsTable nights={nights} places={places} />);

    expect(cellFor('Upper Pines', 'Fri Jul 3', 'available').textContent).toBe('A4');
    expect(cellFor('Upper Pines', 'Sat Jul 4', 'reserved').textContent).toBe('R');
    expect(cellFor('Tuolumne Meadows', 'Fri Jul 3', 'first come first served').textContent).toBe(
      'FF',
    );
    expect(cellFor('Tuolumne Meadows', 'Sat Jul 4', 'closed').textContent).toBe('C');
  });

  // A missing entry is "we do not know", not "nothing there" — the difference
  // between an unanswered question and a bookable night is the point of the block.
  test('a night with no entry reads as unknown', () => {
    render(<NightsTable nights={nights} places={places} />);

    expect(cellFor('Upper Pines', 'Sun Jul 5', 'unknown').textContent).toBe('?');
  });

  // The count is a fact about the open case only, and the union is what enforces
  // that: a reserved cell has nowhere to put a number.
  test('the site count rides along with the open state, and is optional', () => {
    render(<NightsTable nights={nights} places={places} />);

    expect(cellFor('Upper Pines', 'Fri Jul 3', 'available').getAttribute('aria-label')).toContain(
      '4 sites',
    );
    expect(
      cellFor('Tuolumne Meadows', 'Sun Jul 5', 'available').getAttribute('aria-label'),
    ).not.toContain('sites');
  });

  test('the key lists only the states on screen', () => {
    render(<NightsTable nights={nights} places={places} />);

    const keys = screen.getAllByRole('listitem').map((item) => item.textContent);
    expect(keys).toContain('AAvailable');
    expect(keys).toContain('RReserved');
    // No past night is shown, so the key does not explain one.
    expect(keys.some((key) => key?.includes('Past'))).toBe(false);
  });

  test('a place with a page links; one without renders as text', () => {
    render(<NightsTable nights={nights} places={places} />);

    expect(screen.getByRole('link', { name: 'Upper Pines' }).getAttribute('href')).toBe(
      '?poi=upper-pines',
    );
    expect(screen.queryByRole('link', { name: 'Tuolumne Meadows' })).toBeNull();
  });

  test('no nights or no places renders the empty line, not an empty grid', () => {
    const { rerender } = render(<NightsTable nights={[]} places={places} emptyLabel="Nothing yet." />);
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText('Nothing yet.')).toBeTruthy();

    rerender(<NightsTable nights={nights} places={[]} emptyLabel="Nothing yet." />);
    expect(screen.queryByRole('table')).toBeNull();
  });

  test('the heading and caption are the block’s own, and both are optional', () => {
    render(<NightsTable heading="Open nights" caption="Next three nights." nights={nights} places={places} />);

    expect(screen.getByRole('heading', { name: 'Open nights' })).toBeTruthy();
    expect(screen.getByText('Next three nights.')).toBeTruthy();
  });
});
