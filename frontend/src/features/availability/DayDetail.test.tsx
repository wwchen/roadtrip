import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { DayDetail, type WatchUnavailableReason } from './DayDetail';
import type { AvailabilityDay } from '@/api/availability-api';

const reservedDay: AvailabilityDay = {
  date: '2026-09-08',
  status: 'reserved',
  watchable: true,
  cells: {
    '1': { status: 'reserved', watchable: true },
    '2': { status: 'reserved', watchable: true },
  },
};

function renderDetail(
  unavailable: WatchUnavailableReason | null,
  day: AvailabilityDay = reservedDay,
  onSignIn = vi.fn(),
  onRetryWatches = vi.fn(),
) {
  render(
    <DayDetail
      day={day}
      watching={false}
      unavailable={unavailable}
      busy={false}
      onToggleWatch={vi.fn()}
      onRetryWatches={onRetryWatches}
      onSignIn={onSignIn}
    />,
  );
  return { onSignIn, onRetryWatches };
}

describe('the day panel', () => {
  test('offers sign-in from the signed-out message', async () => {
    const { onSignIn } = renderDetail('signed-out');

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(onSignIn).toHaveBeenCalledOnce();
  });

  test('still says what signing in buys', () => {
    renderDetail('signed-out');

    expect(screen.getByText(/to set availability alerts/)).toBeInTheDocument();
  });

  test('offers no sign-in when the provider cannot alert anyone', () => {
    renderDetail('unsupported');

    expect(screen.queryByRole('button', { name: 'Sign in' })).toBeNull();
    expect(screen.getByText(/not available for this campground/i)).toBeInTheDocument();
  });

  test('keeps its retry on a failed lookup', async () => {
    const { onRetryWatches } = renderDetail('failed');

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(onRetryWatches).toHaveBeenCalledOnce();
  });

  test('offers the watch when the day says it is watchable', () => {
    renderDetail(null);

    expect(screen.getByRole('button', { name: 'Set watch' })).toBeInTheDocument();
  });

  test('takes the day"s own watchable flag, not its status', () => {
    // Reserved, and still unwatchable: the provider cannot be internally polled,
    // which is a fact only the backend has.
    renderDetail(null, {
      ...reservedDay,
      watchable: false,
      cells: { '1': { status: 'reserved', watchable: false } },
    });

    expect(screen.queryByRole('button', { name: 'Set watch' })).toBeNull();
    expect(screen.getByText(/no online openings to watch/i)).toBeInTheDocument();
  });
});
