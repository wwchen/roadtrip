import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';

const filters = () => useCampgroundFilterStore.getState();

// The calendar opens on today's month and disables the days before it. September
// 2026's grid also shows Aug 30–31 and Oct 1–10, so day numbers past 10 are the
// unambiguous ones to click.
const TODAY = new Date(2026, 8, 1);

beforeEach(() => {
  filters().reset();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(TODAY);
});

afterEach(() => vi.useRealTimers());

const day = (dayOfMonth: number) => screen.getByRole('button', { name: String(dayOfMonth) });

const mount = (open = true) =>
  render(<FilterPanel open={open} onToggle={() => {}} totalInBoundary={17} totalMatching={17} />);

describe('FilterPanel', () => {
  test('closed, it is the pill with its hint', () => {
    mount(false);

    expect(screen.getByRole('button', { name: /Filter campgrounds/ })).toBeInTheDocument();
    expect(screen.getByText('Then check dates')).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup', { name: 'Site type' })).toBeNull();
  });

  test('open, the site type control writes the store', () => {
    mount();

    fireEvent.click(screen.getByRole('radio', { name: 'Tent' }));

    expect(filters().siteType).toBe('tent');
  });

  test('the stepper turns the group filter on at the minimum and off again below it', () => {
    mount();

    expect(screen.getByRole('group', { name: 'Group size' })).toHaveTextContent('2');

    fireEvent.click(screen.getByRole('button', { name: 'More people' }));
    expect(filters().groupSize).toBe(3);

    fireEvent.click(screen.getByRole('button', { name: 'More people' }));
    expect(filters().groupSize).toBe(4);

    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    expect(filters().groupSize).toBe(3);

    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    expect(filters().groupSize).toBe(2);

    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    expect(filters().groupSize).toBeNull();
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
    render(<FilterPanel open onToggle={() => {}} totalInBoundary={17} totalMatching={9} />);

    expect(screen.getByText('9 of 17 in view')).toBeInTheDocument();
  });

  test('Add dates opens a calendar on this month, and Save stores the picked window only', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    expect(screen.getByText('September 2026')).toBeInTheDocument();
    expect(screen.getByText('Pick a start date')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    fireEvent.click(day(11));
    expect(screen.getByText('Pick an end date')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    fireEvent.click(day(13));
    expect(screen.getByText('2 nights')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
    expect(screen.getByText('Fri Sep 11 → Sun Sep 13 · 2 nights')).toBeInTheDocument();
  });

  test('a click on or before the start begins the window again', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.click(day(13));
    fireEvent.click(day(11));

    expect(screen.getByText('Fri Sep 11')).toBeInTheDocument();
    expect(screen.getByText('Not set')).toBeInTheDocument();
    expect(screen.getByText('Pick an end date')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  test('days before today cannot be picked', () => {
    vi.setSystemTime(new Date(2026, 8, 15));
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));

    expect(day(14)).toBeDisabled();
    expect(day(15)).not.toBeDisabled();
  });

  test('Edit reopens the calendar on the saved window', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

    expect(screen.getByText('October 2026')).toBeInTheDocument();
    expect(screen.getByText('Fri Oct 16')).toBeInTheDocument();
    expect(screen.getByText('Mon Oct 19')).toBeInTheDocument();
    expect(screen.getByText('3 nights')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
  });
});
