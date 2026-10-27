import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AvailabilityDay } from '@/api/availability-api';
import { NightsStrip } from './NightsStrip';
import { dayFull, dayOpen } from './stay-fixtures';

const noop = () => undefined;

describe('NightsStrip', () => {
  test('draws a night the server omitted as unknown and unselectable', async () => {
    const onSelect = vi.fn();
    render(
      <NightsStrip
        dates={['2026-10-25', '2026-10-26']}
        days={[dayOpen('2026-10-25', 3)]}
        selected={null}
        onSelect={onSelect}
      />,
    );

    const omitted = screen.getByRole('button', { name: /26/ });
    expect(omitted).toBeDisabled();
    expect(omitted).toHaveAttribute('data-state', 'unknown');
    await userEvent.click(omitted);
    expect(onSelect).not.toHaveBeenCalled();
  });

  test('a day with no cells is unknown, not borrowed from its neighbour', () => {
    render(
      <NightsStrip
        dates={['2026-10-25', '2026-10-26']}
        days={[dayOpen('2026-10-25', 3), { ...dayOpen('2026-10-26', 0), cells: {} }]}
        selected={null}
        onSelect={noop}
      />,
    );

    expect(screen.getByRole('button', { name: /26/ })).toHaveAttribute('data-state', 'unknown');
  });

  test('a night with sites reports its own count and selects', async () => {
    const onSelect = vi.fn();
    render(
      <NightsStrip dates={['2026-10-25']} days={[dayOpen('2026-10-25', 3)]} selected={null} onSelect={onSelect} />,
    );

    const night = screen.getByRole('button', { name: /25/ });
    expect(night).toHaveAttribute('data-state', 'open');
    expect(night).toHaveAccessibleName(/3 sites open/);
    await userEvent.click(night);
    expect(onSelect).toHaveBeenCalledWith('2026-10-25');
  });

  test('a night whose cells are all reserved is full, and still selectable', async () => {
    const onSelect = vi.fn();
    render(
      <NightsStrip dates={['2026-10-25']} days={[dayFull('2026-10-25')]} selected={null} onSelect={onSelect} />,
    );

    const night = screen.getByRole('button', { name: /25/ });
    expect(night).toHaveAttribute('data-state', 'full');
    expect(night).toHaveAccessibleName(/full/i);
    await userEvent.click(night);
    expect(onSelect).toHaveBeenCalledWith('2026-10-25');
  });

  test('a day whose own rollup says open does not make its empty cells open', () => {
    const rollupOnly: AvailabilityDay = { date: '2026-10-25', status: 'available', watchable: false, cells: {} };
    render(<NightsStrip dates={['2026-10-25']} days={[rollupOnly]} selected={null} onSelect={noop} />);

    expect(screen.getByRole('button', { name: /25/ })).toHaveAttribute('data-state', 'unknown');
  });

  test('marks the selected night as pressed and no other', () => {
    render(
      <NightsStrip
        dates={['2026-10-25', '2026-10-26']}
        days={[dayOpen('2026-10-25', 3), dayOpen('2026-10-26', 2)]}
        selected="2026-10-26"
        onSelect={noop}
      />,
    );

    expect(screen.getByRole('button', { name: /26/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /25/ })).toHaveAttribute('aria-pressed', 'false');
  });

  test('draws every date it is given, in order', () => {
    render(
      <NightsStrip
        dates={['2026-10-27', '2026-10-25', '2026-10-26']}
        days={[]}
        selected={null}
        onSelect={noop}
      />,
    );

    const names = screen.getAllByRole('button').map((button) => button.getAttribute('aria-label'));
    expect(names).toEqual([
      expect.stringContaining('Oct 27'),
      expect.stringContaining('Oct 25'),
      expect.stringContaining('Oct 26'),
    ]);
  });
});
