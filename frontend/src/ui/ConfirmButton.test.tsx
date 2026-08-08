// The double-confirm primitive. Extracted at its third site, so this is also the
// first test coverage the pattern has had in React — Phase 1's inline copy in
// WatchTable was only exercised through the watches page.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfirmButton } from './ConfirmButton';

/** Mirrors ConfirmButton's ARM_TIMEOUT_MS. */
const ARM_TIMEOUT_MS = 5000;

afterEach(() => {
  vi.useRealTimers();
});

describe('ConfirmButton', () => {
  test('the first click does not fire the action', async () => {
    const onConfirm = vi.fn();
    render(<ConfirmButton label="Delete" onConfirm={onConfirm} />);

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));

    expect(onConfirm).not.toHaveBeenCalled();
  });

  test('the second click fires it exactly once', async () => {
    const onConfirm = vi.fn();
    render(<ConfirmButton label="Delete" onConfirm={onConfirm} />);

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  // The accessible name has to change, or a screen reader announces the same
  // control for two different actions.
  test('the accessible name and the label both change when armed', async () => {
    render(<ConfirmButton label="Delete" onConfirm={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));

    const armed = screen.getByRole('button', { name: 'Confirm delete' });
    expect(armed).toHaveTextContent('Delete?');
  });

  test('custom labels are honoured', async () => {
    render(
      <ConfirmButton label="Sign out" confirmLabel="Confirm sign out" onConfirm={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(screen.getByRole('button', { name: 'Confirm sign out' })).toHaveTextContent(
      'Confirm sign out',
    );
  });

  // An armed button left on screen is a trap: the next click on what looks like an
  // ordinary button destroys something.
  test('it disarms itself after the timeout', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const onConfirm = vi.fn();
    render(<ConfirmButton label="Delete" onConfirm={onConfirm} />);

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(screen.getByRole('button', { name: 'Confirm delete' })).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(ARM_TIMEOUT_MS);
    });

    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  // The visible text and the announced name must not diverge when the caller gives
  // explicit confirm wording — a screen reader would otherwise read a different
  // action than the one on screen.
  test('an explicit confirmLabel is also the accessible name', async () => {
    render(
      <ConfirmButton label="Disconnect Slack" confirmLabel="Confirm disconnect" onConfirm={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Disconnect Slack' }));

    const armed = screen.getByRole('button', { name: 'Confirm disconnect' });
    expect(armed).toHaveTextContent('Confirm disconnect');
  });

  // Without one, the terse visible text and the explicit announced name differ on
  // purpose.
  test('with no confirmLabel the name is derived and the text stays terse', async () => {
    render(<ConfirmButton label="Delete" onConfirm={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));

    expect(screen.getByRole('button', { name: 'Confirm delete' })).toHaveTextContent('Delete?');
  });

  test('a disabled button cannot be armed', async () => {
    const onConfirm = vi.fn();
    render(<ConfirmButton label="Delete" disabled onConfirm={onConfirm} />);

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));

    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
