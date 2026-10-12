import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MonthCalendar, type MonthCalendarProps } from './MonthCalendar';

// September 2026 opens on Sunday Aug 30 and runs to Saturday Oct 10, so the
// day numbers 11–29 each appear once.
const mount = (props: Partial<MonthCalendarProps> = {}) => {
  const onPick = vi.fn();
  render(<MonthCalendar initialMonth="2026-09-11" today="2026-09-01" onPick={onPick} {...props} />);
  return onPick;
};

const day = (dayOfMonth: number) => screen.getByRole('button', { name: String(dayOfMonth) });

describe('MonthCalendar', () => {
  test('opens on the month of initialMonth and pages on its own', () => {
    mount();

    expect(screen.getByText('September 2026')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    expect(screen.getByText('October 2026')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    expect(screen.getByText('August 2026')).toBeInTheDocument();
  });

  test('a pick reports the day as YYYY-MM-DD', () => {
    const onPick = mount();

    fireEvent.click(day(14));

    expect(onPick).toHaveBeenCalledWith('2026-09-14');
  });

  test('days outside min and max are disabled', () => {
    mount({ min: '2026-09-12', max: '2026-09-20' });

    expect(day(11)).toBeDisabled();
    expect(day(12)).not.toBeDisabled();
    expect(day(20)).not.toBeDisabled();
    expect(day(21)).toBeDisabled();
  });

  test('a range draws both ends as selected and the days between as in range', () => {
    mount({ selectedStart: '2026-09-11', selectedEnd: '2026-09-14' });

    expect(day(11)).toHaveClass('rt-cal-selected');
    expect(day(12)).toHaveClass('rt-cal-in-range');
    expect(day(13)).toHaveClass('rt-cal-in-range');
    expect(day(14)).toHaveClass('rt-cal-selected');
    expect(day(15)).not.toHaveClass('rt-cal-in-range');
    expect(day(15)).not.toHaveClass('rt-cal-selected');
  });

  test('a start with no end is a single selected day', () => {
    mount({ selectedStart: '2026-09-11' });

    expect(day(11)).toHaveClass('rt-cal-selected');
    expect(day(12)).not.toHaveClass('rt-cal-in-range');
  });
});
