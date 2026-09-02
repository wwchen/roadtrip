import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { WatchSignInGate } from './WatchSignInGate';

const renderGate = (onSignIn = vi.fn(), onClose = vi.fn()) => {
  render(
    <WatchSignInGate
      title="Watch Bowman Bay"
      subtitle="Tuesday, August 11"
      onSignIn={onSignIn}
      onClose={onClose}
    />,
  );
  return { onSignIn, onClose };
};

describe('the watch sign-in gate', () => {
  test('names the campground and the night it would watch', () => {
    renderGate();

    expect(screen.getByText('Watch Bowman Bay')).toBeInTheDocument();
    expect(screen.getByText('Tuesday, August 11')).toBeInTheDocument();
  });

  test('starts the sign-in flow', async () => {
    const { onSignIn } = renderGate();

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(onSignIn).toHaveBeenCalledOnce();
  });

  test('closes without signing in', async () => {
    const { onClose, onSignIn } = renderGate();

    await userEvent.click(screen.getByRole('button', { name: 'Close' }));

    expect(onClose).toHaveBeenCalledOnce();
    expect(onSignIn).not.toHaveBeenCalled();
  });
});
