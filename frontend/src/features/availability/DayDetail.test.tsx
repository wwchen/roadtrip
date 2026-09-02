import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { DayDetail, type WatchUnavailableReason } from './DayDetail';
import type { FusedDay } from './fuse';

const reservedDay: FusedDay = {
  date: '2026-09-08',
  status: 'reserved',
  available_campsite_ids: [],
  campsite_statuses: { '1': 'reserved', '2': 'reserved' },
};

function renderDetail(
  unavailable: WatchUnavailableReason | null,
  onSignIn = vi.fn(),
  onRetryWatches = vi.fn(),
) {
  render(
    <DayDetail
      day={reservedDay}
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
});
