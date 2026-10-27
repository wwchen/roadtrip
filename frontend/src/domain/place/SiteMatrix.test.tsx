import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { AvailabilityDay } from '@/api/availability-api';
import type { Campsite } from '@/api/campsite-api';
import { bookingCopy, gateCopy, matrixCopy } from '@/lib/strings';
import type { CartAction } from './cart-action';
import type { CartGate } from './cart-gate';
import { SiteMatrix } from './SiteMatrix';

const FIRST = '2026-10-27';
const SECOND = '2026-10-28';
const DATES = [FIRST, SECOND];

const noop = () => undefined;

const site = (id: number, extra: Partial<Campsite> = {}): Campsite =>
  ({
    id,
    campground_id: 1,
    name: String(id),
    kind: 'tent',
    kind_label: 'Tent',
    equipment: [],
    attributes: [],
    data_provider: 'rec_gov',
    data_provider_ref: String(id),
    ...extra,
  }) as Campsite;

const day = (date: string, cells: AvailabilityDay['cells']): AvailabilityDay => ({
  date,
  status: 'available',
  watchable: false,
  cells,
});

const open = { status: 'available', watchable: false } as const;
const taken = { status: 'reserved', watchable: false } as const;

const WEEK = [day(FIRST, { 1: open, 2: taken }), day(SECOND, { 1: taken, 2: taken })];

const CATALOG = [
  site(1, { name: '12', loop_name: 'Loop A' }),
  site(2, { name: '03', loop_name: 'Loop B', kind: 'rv', kind_label: 'RV' }),
];

const grid = (over: Partial<Parameters<typeof SiteMatrix>[0]> = {}) => (
  <SiteMatrix dates={DATES} days={WEEK} campsites={CATALOG} selected={null} onSelect={noop} {...over} />
);

describe('SiteMatrix', () => {
  test('draws one column per night in view and one row per campsite', () => {
    render(grid());

    const headers = screen.getAllByRole('columnheader');
    expect(headers).toHaveLength(DATES.length + 1);
    expect(headers[0]).toHaveTextContent(matrixCopy.siteColumn);
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Loop A / 12',
      'Loop B / 03',
    ]);
  });

  test('a cell is the status the wire gave that site that night, glyph and all', () => {
    render(grid());

    expect(screen.getByLabelText('Loop A / 12 Tue, Oct 27: available')).toHaveTextContent('A');
    expect(screen.getByLabelText('Loop A / 12 Wed, Oct 28: reserved')).toHaveTextContent('R');
  });

  test('a night the wire never answered reads as unknown, not as the rollup', () => {
    render(grid({ dates: [FIRST, SECOND, '2026-10-29'], days: [WEEK[0]] }));

    expect(screen.getByLabelText('Loop A / 12 Thu, Oct 29: unknown')).toHaveTextContent('?');
  });

  test('the free-text filter narrows the rows and the heading says so', () => {
    render(grid());
    expect(screen.getByRole('heading')).toHaveTextContent(matrixCopy.heading(2, 2));

    fireEvent.change(screen.getByRole('searchbox', { name: matrixCopy.filterSites }), {
      target: { value: 'Loop B' },
    });

    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(['Loop B / 03']);
    expect(screen.getByRole('heading')).toHaveTextContent(matrixCopy.heading(1, 2));
  });

  test('the loop and type filters offer what the catalog actually carries', () => {
    render(grid());

    const loops = screen.getByRole('combobox', { name: matrixCopy.filterByLoop });
    expect([...loops.querySelectorAll('option')].map((o) => o.textContent)).toEqual([
      matrixCopy.allLoops,
      'Loop A',
      'Loop B',
    ]);

    const types = screen.getByRole('combobox', { name: matrixCopy.filterByType });
    expect([...types.querySelectorAll('option')].map((o) => o.textContent)).toEqual([
      matrixCopy.allTypes,
      'RV',
      'Tent',
    ]);
  });

  test('available-first is the order it opens in, and the sort can be changed', () => {
    render(grid());
    expect(screen.getAllByRole('rowheader')[0]).toHaveTextContent('Loop A / 12');

    fireEvent.change(screen.getByRole('combobox', { name: matrixCopy.sortSites }), {
      target: { value: 'site' },
    });

    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Loop B / 03',
      'Loop A / 12',
    ]);
  });

  test('filters that match nothing say so instead of drawing an empty table', () => {
    render(grid());

    fireEvent.change(screen.getByRole('searchbox', { name: matrixCopy.filterSites }), {
      target: { value: 'nothing here' },
    });

    expect(screen.getByText(matrixCopy.noMatches)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  test('a campground with no reservable sites says so', () => {
    render(grid({ days: [], campsites: [] }));

    expect(screen.getByText(matrixCopy.noSites)).toBeInTheDocument();
  });

  test('draws nothing at all when there are no nights in view', () => {
    const { container } = render(grid({ dates: [] }));

    expect(container).toBeEmptyDOMElement();
  });

  test('it draws rows from the cells when the catalog has not landed', () => {
    render(grid({ campsites: undefined }));

    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Site #1',
      'Site #2',
    ]);
  });

  test('a date header picks that night, so dropping back to half lands on it', () => {
    const onSelect = vi.fn();
    render(grid({ onSelect, selected: FIRST }));

    const picked = screen.getByRole('button', { name: matrixCopy.pickNight('Tue, Oct 27') });
    expect(picked).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByRole('button', { name: matrixCopy.pickNight('Wed, Oct 28') }));
    expect(onSelect).toHaveBeenCalledWith(SECOND);
  });

  test('the grid tells its own CSS how many nights are in view', () => {
    const { container } = render(grid({ dates: [FIRST, SECOND, '2026-10-29'] }));
    const scroll = container.querySelector('.rt-site-matrix__scroll') as HTMLElement;

    expect(scroll.style.getPropertyValue('--rt-site-date-count')).toBe('3');
  });

  test('the key names every state a cell can take, by glyph and in words', () => {
    render(grid());
    const key = screen.getByRole('list', { name: matrixCopy.key });

    expect([...key.querySelectorAll('li')].map((item) => item.textContent)).toEqual([
      'AAvailable',
      'FFFirst come first served',
      'RReserved',
      'CClosed',
      '?Unknown',
    ]);
  });
});

const OPEN_CELL = 'Loop A / 12 Tue, Oct 27: available';
const TAKEN_CELL = 'Loop A / 12 Wed, Oct 28: reserved';
const OPEN_ID = '1';

const cartCell = () => screen.getByRole('button', { name: matrixCopy.cellCart(OPEN_CELL) });

describe('SiteMatrix, holding a site in the cart', () => {
  test('a reader with no cart at this campground gets a cell that is text, not a dead control', () => {
    render(grid({ cart: { state: 'unsupported' } }));

    expect(screen.getByLabelText(OPEN_CELL)).toHaveTextContent('A');
    expect(screen.queryByRole('button', { name: /add to cart/i })).toBeNull();
  });

  test('a caller given no cart at all is treated the same way', () => {
    render(grid());

    expect(screen.getByLabelText(OPEN_CELL)).toHaveTextContent('A');
    expect(screen.queryByRole('button', { name: /add to cart/i })).toBeNull();
  });

  test('an open cell becomes the control once the cart is reachable', () => {
    render(grid({ cart: { state: 'ready', onAddToCart: noop } }));

    expect(cartCell()).toHaveAttribute('aria-expanded', 'false');
    expect(cartCell()).toHaveTextContent('A');
  });

  test('a night that is not open is never a cart control, however reachable the cart', () => {
    render(grid({ cart: { state: 'ready', onAddToCart: noop } }));

    expect(screen.getByLabelText(TAKEN_CELL)).toHaveTextContent('R');
    expect(screen.queryByRole('button', { name: matrixCopy.cellCart(TAKEN_CELL) })).toBeNull();
  });

  test('clicking an open cell offers the hold for that site and night', () => {
    const onAddToCart = vi.fn();
    render(grid({ cart: { state: 'ready', onAddToCart } }));

    fireEvent.click(cartCell());

    expect(cartCell()).toHaveAttribute('aria-expanded', 'true');
    const group = screen.getByRole('group', { name: bookingCopy.addToCart });
    expect(group).toHaveTextContent('Loop A / 12');
    expect(group).toHaveTextContent('Tue, Oct 27');

    fireEvent.click(screen.getByRole('button', { name: bookingCopy.addToCart }));
    expect(onAddToCart).toHaveBeenCalledExactlyOnceWith(OPEN_ID, FIRST);
  });

  test('a second click on the control cannot start a second hold', () => {
    const onAddToCart = vi.fn();
    const pending: CartAction = { kind: 'pending', cell: { campsiteId: OPEN_ID, date: FIRST } };
    render(grid({ cart: { state: 'ready', onAddToCart }, cartAction: pending }));

    const cell = screen.getByRole('button', { name: matrixCopy.cellHolding(OPEN_CELL) });
    fireEvent.click(cell);

    const offer = screen.getByRole('button', { name: bookingCopy.addToCart });
    expect(offer).toBeDisabled();
    fireEvent.click(offer);
    expect(onAddToCart).not.toHaveBeenCalled();
  });

  test('a held cell says so from the grid, without claiming anything about the stay', () => {
    const held: CartAction = {
      kind: 'held',
      cell: { campsiteId: OPEN_ID, date: FIRST },
      cartUrl: 'https://cart',
    };
    render(grid({ cart: { state: 'ready', onAddToCart: noop }, cartAction: held }));

    expect(screen.getByRole('button', { name: matrixCopy.cellHeld(OPEN_CELL) })).toBeInTheDocument();
    expect(screen.getByLabelText(TAKEN_CELL)).toHaveTextContent('R');
  });

  test('a gated reader is offered the one step that unlocks the hold', () => {
    const gate: CartGate = { state: 'signed_out', onSignIn: noop };
    render(grid({ cart: gate }));

    fireEvent.click(cartCell());

    expect(screen.getByText(gateCopy.cartSignedOut)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: new RegExp(bookingCopy.addToCart) })).toBeEnabled();
  });
});
