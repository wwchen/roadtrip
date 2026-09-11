import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { CellBookPopover, type CellCart } from './CellBookPopover';

const anchors: HTMLElement[] = [];

function renderPopover(cart: CellCart, onOpenBooking = vi.fn(), bookingSystem?: string) {
  const anchor = document.body.appendChild(document.createElement('button'));
  anchors.push(anchor);
  render(
    <CellBookPopover
      anchor={anchor}
      onOpenBooking={onOpenBooking}
      cart={cart}
      onClose={vi.fn()}
      bookingSystem={bookingSystem}
    />,
  );
  return { onOpenBooking };
}

const cartRow = () => screen.getByRole('button', { name: /add to cart/i });

afterEach(() => {
  anchors.splice(0).forEach((anchor) => anchor.remove());
});

describe('the cell booking popover', () => {
  test('always offers the provider’s own booking page, named for the campground’s own vendor', async () => {
    const { onOpenBooking } = renderPopover(
      { state: 'signed-out', onSignIn: vi.fn() },
      undefined,
      'Campflare',
    );

    await userEvent.click(screen.getByRole('button', { name: 'Book on Campflare' }));

    expect(onOpenBooking).toHaveBeenCalledOnce();
  });

  test('names no vendor of its own when the campground’s booking system is unknown', () => {
    renderPopover({ state: 'signed-out', onSignIn: vi.fn() });

    expect(screen.getByRole('button', { name: 'Book on the booking site' })).toBeInTheDocument();
  });

  test('holds the site when the caller may drive the cart', async () => {
    const onAddToCart = vi.fn();
    renderPopover({ state: 'ready', onAddToCart, busy: false });

    await userEvent.click(cartRow());

    expect(onAddToCart).toHaveBeenCalledOnce();
  });

  test('tells a signed-out visitor what unlocks the cart, and starts it', async () => {
    const onSignIn = vi.fn();
    renderPopover({ state: 'signed-out', onSignIn });

    expect(screen.getByText('Sign in to hold sites from here')).toBeInTheDocument();
    await userEvent.click(cartRow());

    expect(onSignIn).toHaveBeenCalledOnce();
  });

  test('sends a user without rec.gov credentials to Settings', async () => {
    const onOpenSettings = vi.fn();
    renderPopover({ state: 'no-credentials', onOpenSettings });

    expect(screen.getByText('Add rec.gov login in Settings')).toBeInTheDocument();
    await userEvent.click(cartRow());

    expect(onOpenSettings).toHaveBeenCalledOnce();
  });

  test('locks the cart row while a hold is already running', () => {
    renderPopover({ state: 'ready', onAddToCart: vi.fn(), busy: true });

    expect(cartRow()).toBeDisabled();
  });
});
