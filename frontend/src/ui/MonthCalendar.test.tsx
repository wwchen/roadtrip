import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MonthCalendar, type MonthCalendarProps } from './MonthCalendar';

const mount = (props: Partial<MonthCalendarProps> = {}) => {
  const onPick = vi.fn();
  render(<MonthCalendar initialMonth="2026-09-11" today="2026-09-01" onPick={onPick} {...props} />);
  return onPick;
};

const day = (name: string) => screen.getByRole('button', { name });

describe('MonthCalendar', () => {
  test('opens on the month of initialMonth and pages on its own', () => {
    mount();

    expect(screen.getByRole('group', { name: 'September 2026' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    expect(screen.getByRole('group', { name: 'October 2026' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    expect(screen.getByRole('group', { name: 'August 2026' })).toBeInTheDocument();
  });

  test('names every day in full, so a padding day says which month it is in', () => {
    mount();

    expect(day('Wednesday, September 30, 2026')).toHaveTextContent('30');
    expect(day('Sunday, August 30, 2026')).toHaveTextContent('30');
    expect(day('Tuesday, September 1, 2026')).toHaveAttribute('aria-current', 'date');
  });

  test('a pick reports the day as YYYY-MM-DD', () => {
    const onPick = mount();

    fireEvent.click(day('Monday, September 14, 2026'));

    expect(onPick).toHaveBeenCalledWith('2026-09-14');
  });

  test('days outside min and max are disabled', () => {
    mount({ min: '2026-09-12', max: '2026-09-20' });

    expect(day('Friday, September 11, 2026')).toBeDisabled();
    expect(day('Saturday, September 12, 2026')).not.toBeDisabled();
    expect(day('Sunday, September 20, 2026')).not.toBeDisabled();
    expect(day('Monday, September 21, 2026')).toBeDisabled();
  });

  test('a range is pressed end to end, with the nights between drawn in range', () => {
    mount({ selectedStart: '2026-09-11', selectedEnd: '2026-09-14' });

    expect(day('Thursday, September 10, 2026')).toHaveAttribute('aria-pressed', 'false');
    expect(day('Friday, September 11, 2026')).toHaveAttribute('aria-pressed', 'true');
    expect(day('Friday, September 11, 2026')).toHaveClass('rt-cal-selected');
    expect(day('Saturday, September 12, 2026')).toHaveAttribute('aria-pressed', 'true');
    expect(day('Saturday, September 12, 2026')).toHaveClass('rt-cal-in-range');
    expect(day('Monday, September 14, 2026')).toHaveClass('rt-cal-selected');
    expect(day('Tuesday, September 15, 2026')).toHaveAttribute('aria-pressed', 'false');
  });

  test('a start with no end is a single pressed day', () => {
    mount({ selectedStart: '2026-09-11' });

    expect(day('Friday, September 11, 2026')).toHaveAttribute('aria-pressed', 'true');
    expect(day('Saturday, September 12, 2026')).toHaveAttribute('aria-pressed', 'false');
  });

  test('the grid is one Tab stop, on the selected day when there is one', () => {
    mount({ selectedStart: '2026-09-11' });

    const days = screen.getByRole('group', { name: 'September 2026' }).querySelectorAll('button');
    const tabbable = [...days].filter((button) => button.tabIndex === 0);
    expect(tabbable.map((button) => button.getAttribute('aria-label'))).toEqual(['Friday, September 11, 2026']);
  });

  test('arrow keys move the focus by day and week, and the focus follows into the next month', () => {
    mount({ selectedStart: '2026-09-11' });
    const start = day('Friday, September 11, 2026');
    start.focus();

    fireEvent.keyDown(start, { key: 'ArrowRight' });
    expect(day('Saturday, September 12, 2026')).toHaveFocus();

    fireEvent.keyDown(document.activeElement!, { key: 'ArrowDown' });
    expect(day('Saturday, September 19, 2026')).toHaveFocus();

    fireEvent.keyDown(document.activeElement!, { key: 'PageDown' });
    expect(screen.getByRole('group', { name: 'October 2026' })).toBeInTheDocument();
    expect(day('Monday, October 19, 2026')).toHaveFocus();
    expect(day('Monday, October 19, 2026')).toHaveAttribute('tabindex', '0');
  });

  test('Shift+PageDown jumps a year', () => {
    mount({ selectedStart: '2026-09-11' });
    const start = day('Friday, September 11, 2026');
    start.focus();

    fireEvent.keyDown(start, { key: 'PageDown', shiftKey: true });

    expect(screen.getByRole('group', { name: 'September 2027' })).toBeInTheDocument();
    expect(day('Saturday, September 11, 2027')).toHaveFocus();
  });

  test('the focus does not move onto a day that cannot be picked', () => {
    mount({ min: '2026-09-11', selectedStart: '2026-09-11' });
    const start = day('Friday, September 11, 2026');
    start.focus();

    fireEvent.keyDown(start, { key: 'ArrowLeft' });

    expect(start).toHaveFocus();
  });
});
