import { beforeEach, describe, expect, test } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';

const filters = () => useCampgroundFilterStore.getState();

beforeEach(() => filters().reset());

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

  test('Add dates reveals the fields and Save stores the window only', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.input(screen.getByLabelText('Start date'), { target: { value: '2026-09-11' } });
    fireEvent.input(screen.getByLabelText('End date'), { target: { value: '2026-09-13' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
    expect(screen.getByText('Fri Sep 11 → Sun Sep 13 · 2 nights')).toBeInTheDocument();
  });

  test('an end date before the start disables Save and explains', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.input(screen.getByLabelText('Start date'), { target: { value: '2026-09-13' } });
    fireEvent.input(screen.getByLabelText('End date'), { target: { value: '2026-09-11' } });

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByText('The end date must be after the start date.')).toBeInTheDocument();
  });

  test('non-ISO dates disable Save and show format hint', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.input(screen.getByLabelText('Start date'), { target: { value: '9/11/2026' } });
    fireEvent.input(screen.getByLabelText('End date'), { target: { value: '9/13/2026' } });

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByText('Enter dates as YYYY-MM-DD.')).toBeInTheDocument();
  });
});
