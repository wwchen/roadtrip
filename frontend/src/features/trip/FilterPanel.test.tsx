import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';

const filters = () => useCampgroundFilterStore.getState();

// The calendar opens on today's month and disables the days before it.
const TODAY = new Date(2026, 8, 1);

beforeEach(() => {
  filters().reset();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(TODAY);
});

afterEach(() => vi.useRealTimers());

const day = (name: string) => screen.getByRole('button', { name });
const field = (name: string) => screen.getByRole('button', { name });
const calendar = () => screen.queryByRole('group', { name: /\d{4}$/ });
/** `useDismiss` attaches its outside-click listener a tick after the calendar opens. */
const nextTick = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));

const AGENCY_COUNTS = new Map([
  ['National Park Service', 9],
  ['USDA Forest Service', 21],
  ['California State Parks', 14],
]);

const mount = (open = true) =>
  render(
    <FilterPanel
      open={open}
      onToggle={() => {}}
      totalInBoundary={17}
      totalMatching={17}
      agencyCounts={AGENCY_COUNTS}
      checkableIds={[]}
    />,
  );

const agencyField = () => screen.getByRole('button', { name: /^Agency/ });
const agencyMenu = () => screen.getByRole('group', { name: 'Agency' });

describe('FilterPanel', () => {
  test('closed, it is the Filter pill and the count in view', () => {
    mount(false);

    expect(screen.getByRole('button', { name: 'Filter' })).toHaveTextContent('Filter');
    expect(screen.getByText('17 in view')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull();
    expect(screen.queryByRole('radiogroup', { name: 'Site type' })).toBeNull();
  });

  test('an active filter shows on the pill, its name, and a Clear beside the count', () => {
    filters().toggleAgency('National Park Service');
    filters().setSiteType('tent');
    mount(false);

    expect(screen.getByRole('button', { name: 'Filter, 2 active' })).toHaveTextContent('Filter2');
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
  });

  test('Clear empties the catalog filters and the agency pick, and keeps the dates', () => {
    filters().toggleAgency('National Park Service');
    filters().toggleAmenity('water');
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(filters().agencies).toEqual([]);
    expect(filters().amenities).toEqual([]);
    expect(filters().dateWindow).toEqual({ start: '2026-10-16', end: '2026-10-19' });
    expect(screen.getByRole('button', { name: 'Filter' })).toBeInTheDocument();
  });

  test('the agency field reads All agencies, and lists agencies by count under the All row', () => {
    mount();

    expect(agencyField()).toHaveAccessibleName('Agency All agencies');
    fireEvent.click(agencyField());

    const rows = within(agencyMenu())
      .getAllByRole('checkbox')
      .map((box) => box.closest('label')?.textContent);
    expect(rows).toEqual(['All agencies', 'USDA Forest Service', 'California State Parks', 'National Park Service']);
    expect(within(agencyMenu()).getByText('21')).toBeInTheDocument();
  });

  test('picking agencies shows only those: the store, the field and the pill all follow', () => {
    mount();
    fireEvent.click(agencyField());

    fireEvent.click(within(agencyMenu()).getByLabelText('National Park Service'));
    fireEvent.click(within(agencyMenu()).getByLabelText('USDA Forest Service'));

    expect(filters().agencies).toEqual(['National Park Service', 'USDA Forest Service']);
    expect(agencyField()).toHaveAccessibleName('Agency USDA Forest Service +1');
    expect(screen.getByRole('button', { name: 'Filter, 1 active' })).toBeInTheDocument();
  });

  test('a picked agency that has left the view stays listed at zero, so it can be unticked', () => {
    filters().toggleAgency('BC Parks');
    mount();
    fireEvent.click(agencyField());

    const gone = within(agencyMenu()).getByLabelText('BC Parks');
    expect(gone).toBeChecked();
    fireEvent.click(gone);

    expect(filters().agencies).toEqual([]);
  });

  test('every control carries a visible label', () => {
    mount();

    for (const label of ['Agency', 'Site type', 'Group size', 'Amenities', 'Start date', 'End date']) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole('group', { name: 'Amenities' })).toBeInTheDocument();
  });

  test('open, the site type control writes the store', () => {
    mount();

    fireEvent.click(screen.getByRole('radio', { name: 'Tent' }));

    expect(filters().siteType).toBe('tent');
  });

  test('the stepper reads Any when off, turns on at the minimum, and goes back to Any below it', () => {
    mount();

    expect(screen.getByRole('group', { name: 'Group size' })).toHaveTextContent('Any');

    fireEvent.click(screen.getByRole('button', { name: 'More people' }));
    expect(filters().groupSize).toBe(2);
    expect(screen.getByRole('group', { name: 'Group size' })).toHaveTextContent('2');

    fireEvent.click(screen.getByRole('button', { name: 'More people' }));
    expect(filters().groupSize).toBe(3);

    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    expect(filters().groupSize).toBe(2);

    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    expect(filters().groupSize).toBeNull();
    expect(screen.getByRole('group', { name: 'Group size' })).toHaveTextContent('Any');
  });

  test('amenity chips toggle', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Toilets' }));
    expect(filters().amenities).toEqual(['toilets']);

    fireEvent.click(screen.getByRole('button', { name: 'Toilets' }));
    expect(filters().amenities).toEqual([]);
  });

  test('the head reports matches against the view once a filter is active', () => {
    filters().setSiteType('tent');
    render(
      <FilterPanel
        open
        onToggle={() => {}}
        totalInBoundary={17}
        totalMatching={9}
        agencyCounts={AGENCY_COUNTS}
        checkableIds={[]}
      />,
    );

    expect(screen.getByText('9 of 17 in view')).toBeInTheDocument();
  });

  test('the date fields sit in the block, with the calendar hidden until one is selected', () => {
    mount();

    expect(screen.queryByRole('button', { name: 'Add dates' })).toBeNull();
    expect(field('Start date, not set')).toHaveAttribute('aria-expanded', 'false');
    expect(field('End date, not set')).toHaveAttribute('aria-expanded', 'false');
    expect(calendar()).toBeNull();
    expect(screen.getByText('Add dates to see which of these have a site.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
  });

  test('the start field opens the calendar, and the window is stored once both ends are picked', () => {
    mount();

    fireEvent.click(field('Start date, not set'));
    expect(field('Start date, not set')).toHaveAttribute('aria-expanded', 'true');
    expect(calendar()).toHaveAccessibleName('September 2026');
    expect(screen.getByText('Pick a start date')).toBeInTheDocument();

    fireEvent.click(day('Friday, September 11, 2026'));
    expect(field('End date, not set')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Pick an end date')).toBeInTheDocument();
    expect(filters().dateWindow).toBeNull();

    fireEvent.click(day('Sunday, September 13, 2026'));
    expect(calendar()).toBeNull();
    expect(field('End date, Sun Sep 13')).toHaveFocus();
    expect(screen.getByText('2 nights')).toBeInTheDocument();
    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
  });

  test('selecting the open field again hides the calendar', () => {
    mount();

    fireEvent.click(field('Start date, not set'));
    fireEvent.click(field('Start date, not set'));

    expect(calendar()).toBeNull();
    expect(field('Start date, not set')).toHaveAttribute('aria-expanded', 'false');
  });

  test('Escape hides the calendar and hands focus back to the field', () => {
    mount();

    fireEvent.click(field('Start date, not set'));
    const today = day('Tuesday, September 1, 2026');
    today.focus();
    fireEvent.keyDown(today, { key: 'Escape' });

    expect(calendar()).toBeNull();
    expect(field('Start date, not set')).toHaveFocus();
  });

  test('a click outside the fields and the calendar hides it', async () => {
    mount();

    fireEvent.click(field('Start date, not set'));
    await nextTick();
    fireEvent.click(document.body);

    expect(calendar()).toBeNull();
  });

  test('an end pick on or before the start begins the window again', () => {
    mount();

    fireEvent.click(field('Start date, not set'));
    fireEvent.click(day('Sunday, September 13, 2026'));
    fireEvent.click(day('Friday, September 11, 2026'));

    expect(field('Start date, Fri Sep 11')).toBeInTheDocument();
    expect(field('End date, not set')).toHaveAttribute('aria-expanded', 'true');
    expect(filters().dateWindow).toBeNull();
  });

  test('days before today cannot be picked', () => {
    vi.setSystemTime(new Date(2026, 8, 15));
    mount();

    fireEvent.click(field('Start date, not set'));

    expect(day('Monday, September 14, 2026')).toBeDisabled();
    expect(day('Tuesday, September 15, 2026')).not.toBeDisabled();
  });

  test('a stored window shows in the fields, with the calendar hidden', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    expect(field('Start date, Fri Oct 16')).toBeInTheDocument();
    expect(field('End date, Mon Oct 19')).toBeInTheDocument();
    expect(calendar()).toBeNull();
    expect(screen.getByText('3 nights')).toBeInTheDocument();
  });

  test('the end field moves only the end of a stored window', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(field('End date, Mon Oct 19'));
    expect(calendar()).toHaveAccessibleName('October 2026');
    fireEvent.click(day('Tuesday, October 20, 2026'));

    expect(calendar()).toBeNull();
    expect(filters().dateWindow).toEqual({ start: '2026-10-16', end: '2026-10-20' });
  });

  test('starting a new window drops the stored one until it is whole', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(field('Start date, Fri Oct 16'));
    fireEvent.click(day('Friday, October 23, 2026'));

    expect(field('End date, not set')).toHaveAttribute('aria-expanded', 'true');
    expect(filters().dateWindow).toBeNull();
  });

  test('the check button appears with both dates, and waits for something checkable', () => {
    mount();
    expect(screen.queryByRole('button', { name: 'Check availability' })).toBeNull();

    fireEvent.click(field('Start date, not set'));
    fireEvent.click(day('Friday, September 11, 2026'));
    fireEvent.click(day('Sunday, September 13, 2026'));

    // Nothing in view can be checked online in this mount.
    expect(screen.getByRole('button', { name: 'Check availability' })).toBeDisabled();
  });

  test('Clear empties both fields and the stored window', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));

    expect(field('Start date, not set')).toBeInTheDocument();
    expect(field('End date, not set')).toBeInTheDocument();
    expect(filters().dateWindow).toBeNull();
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
  });

  test('a stored window that has begun opens without its passed start, and leaves the store', () => {
    vi.setSystemTime(new Date(2026, 9, 18));
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    expect(field('Start date, not set')).toBeInTheDocument();
    expect(field('End date, Mon Oct 19')).toBeInTheDocument();
    expect(filters().dateWindow).toBeNull();

    fireEvent.click(field('Start date, not set'));
    fireEvent.click(day('Sunday, October 18, 2026'));
    expect(calendar()).toBeNull();
    expect(filters().dateWindow).toEqual({ start: '2026-10-18', end: '2026-10-19' });
  });
});
