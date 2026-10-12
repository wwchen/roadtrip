import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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
const slot = (name: string) => screen.getByRole('radio', { name });
const save = () => screen.getByRole('button', { name: 'Save' });

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
    expect(screen.getByRole('group', { name: 'September 2026' })).toBeInTheDocument();
    expect(slot('Start · not set')).toBeChecked();
    expect(screen.getByText('Pick a start date')).toBeInTheDocument();
    expect(save()).toBeDisabled();

    fireEvent.click(day('Friday, September 11, 2026'));
    expect(slot('End · not set')).toBeChecked();
    expect(screen.getByText('Pick an end date')).toBeInTheDocument();
    expect(save()).toBeDisabled();

    fireEvent.click(day('Sunday, September 13, 2026'));
    expect(slot('Start · Fri Sep 11')).toBeChecked();
    expect(slot('End · Sun Sep 13')).toBeInTheDocument();
    expect(screen.getByText('2 nights')).toBeInTheDocument();
    fireEvent.click(save());

    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
    expect(screen.getByText('Fri Sep 11 → Sun Sep 13 · 2 nights')).toBeInTheDocument();
  });

  test('an end pick on or before the start begins the window again', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.click(day('Sunday, September 13, 2026'));
    fireEvent.click(day('Friday, September 11, 2026'));

    expect(slot('Start · Fri Sep 11')).toBeInTheDocument();
    expect(slot('End · not set')).toBeChecked();
    expect(screen.getByText('Pick an end date')).toBeInTheDocument();
    expect(save()).toBeDisabled();
  });

  test('days before today cannot be picked', () => {
    vi.setSystemTime(new Date(2026, 8, 15));
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));

    expect(day('Monday, September 14, 2026')).toBeDisabled();
    expect(day('Tuesday, September 15, 2026')).not.toBeDisabled();
  });

  test('Edit reopens the calendar on the saved window', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

    expect(screen.getByRole('group', { name: 'October 2026' })).toBeInTheDocument();
    expect(slot('Start · Fri Oct 16')).toBeChecked();
    expect(slot('End · Mon Oct 19')).toBeInTheDocument();
    expect(screen.getByText('3 nights')).toBeInTheDocument();
    expect(save()).not.toBeDisabled();
  });

  test('choosing End first moves only the end of a saved window', () => {
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.click(slot('End · Mon Oct 19'));
    fireEvent.click(day('Tuesday, October 20, 2026'));
    fireEvent.click(save());

    expect(filters().dateWindow).toEqual({ start: '2026-10-16', end: '2026-10-20' });
  });

  test('a saved window that has begun reopens without its passed start, and cannot be saved as it was', () => {
    vi.setSystemTime(new Date(2026, 9, 18));
    filters().setDateWindow({ start: '2026-10-16', end: '2026-10-19' });
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

    expect(slot('Start · not set')).toBeChecked();
    expect(slot('End · Mon Oct 19')).toBeInTheDocument();
    expect(save()).toBeDisabled();

    fireEvent.click(day('Sunday, October 18, 2026'));
    fireEvent.click(save());
    expect(filters().dateWindow).toEqual({ start: '2026-10-18', end: '2026-10-19' });
  });
});
