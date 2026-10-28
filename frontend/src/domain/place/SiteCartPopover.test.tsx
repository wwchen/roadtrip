import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { settingsErrorMessage } from '@/lib/settings-errors';
import { bookingCopy, gateCopy } from '@/lib/strings';
import { SiteCartPopover, type SiteCartPopoverProps } from './SiteCartPopover';
import { NO_CART_ACTIONS, cartActionsOf, type CartAction } from './cart-action';

const CAMPSITE_ID = '131744';
const NIGHT = '2026-10-29';
const CELL = { campsiteId: CAMPSITE_ID, date: NIGHT };
const ELSEWHERE = { campsiteId: '131745', date: NIGHT };
const SITE = 'Loop A / 12';
const SPOKEN = 'Thu, Oct 29';
const HOLDER = 'Recreation.gov';
const CART_URL = 'https://www.recreation.gov/cart';
const HELD_AT = 1_767_225_600_000;

const anchorFor = () => {
  const anchor = document.createElement('button');
  document.body.append(anchor);
  return anchor;
};

const show = (over: Partial<SiteCartPopoverProps> = {}) => {
  const props: SiteCartPopoverProps = {
    anchor: anchorFor(),
    cell: CELL,
    site: SITE,
    night: SPOKEN,
    gate: { state: 'ready', onAddToCart: () => undefined },
    actions: NO_CART_ACTIONS,
    onClose: () => undefined,
    ...over,
  };
  return render(<SiteCartPopover {...props} />);
};

const step = () => screen.getByRole('button', { name: new RegExp(bookingCopy.addToCart) });

describe('SiteCartPopover', () => {
  test('it says which site and which night the hold would be for', () => {
    show();

    const group = screen.getByRole('group', { name: bookingCopy.addToCart });
    expect(group).toHaveTextContent(SITE);
    expect(group).toHaveTextContent(SPOKEN);
  });

  test('ready offers the hold itself, for that cell', () => {
    const onAddToCart = vi.fn();
    show({ gate: { state: 'ready', onAddToCart } });

    fireEvent.click(step());

    expect(onAddToCart).toHaveBeenCalledExactlyOnceWith(CAMPSITE_ID, NIGHT);
    expect(step()).toHaveAccessibleName(bookingCopy.addToCart);
  });

  test('signed out keeps the control and makes signing in its action', () => {
    const onSignIn = vi.fn();
    show({ gate: { state: 'signed_out', onSignIn } });

    expect(screen.getByText(gateCopy.cartSignedOut)).toBeInTheDocument();
    expect(step()).toHaveAccessibleName(`${bookingCopy.addToCart} — ${gateCopy.cartSignedOut}`);

    fireEvent.click(step());
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });

  test('no credentials keeps the control and sends the caller to Settings', () => {
    const onOpenSettings = vi.fn();
    show({ gate: { state: 'no_credentials', onOpenSettings, holder: HOLDER } });

    expect(screen.getByText(gateCopy.cartNoCredentials(HOLDER))).toBeInTheDocument();

    fireEvent.click(step());
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
  });

  test('an unnamed provider still reads as a whole sentence', () => {
    show({ gate: { state: 'no_credentials', onOpenSettings: () => undefined } });

    expect(screen.getByText(gateCopy.cartNoCredentials())).toBeInTheDocument();
  });

  test('a running hold on this cell locks the control rather than offering a second', () => {
    const onAddToCart = vi.fn();
    show({
      gate: { state: 'ready', onAddToCart },
      actions: cartActionsOf({ kind: 'pending', cell: CELL }),
    });

    expect(step()).toBeDisabled();
    expect(step()).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('status')).toHaveTextContent(bookingCopy.holdRunning);

    fireEvent.click(step());
    expect(onAddToCart).not.toHaveBeenCalled();
  });

  test('a hold running on another cell locks this control too, and says why', () => {
    const onAddToCart = vi.fn();
    show({
      gate: { state: 'ready', onAddToCart },
      actions: cartActionsOf({ kind: 'pending', cell: ELSEWHERE }),
    });

    expect(step()).toBeDisabled();
    expect(step()).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText(bookingCopy.holdBusy)).toBeInTheDocument();
    expect(step()).toHaveAccessibleName(`${bookingCopy.addToCart} — ${bookingCopy.holdBusy}`);

    fireEvent.click(step());
    expect(onAddToCart).not.toHaveBeenCalled();
  });

  test('a gated control is locked by someone else\'s hold as well', () => {
    const onSignIn = vi.fn();
    show({
      gate: { state: 'signed_out', onSignIn },
      actions: cartActionsOf({ kind: 'pending', cell: ELSEWHERE }),
    });

    expect(step()).toBeDisabled();
    fireEvent.click(step());
    expect(onSignIn).not.toHaveBeenCalled();
  });

  test('a held site says whose cart it is in, how long, and links to it', () => {
    const held: CartAction = {
      kind: 'held',
      cell: CELL,
      cartUrl: CART_URL,
      heldAt: HELD_AT,
      holder: HOLDER,
    };
    show({ actions: cartActionsOf(held) });

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent(bookingCopy.heldTitle(HOLDER));
    expect(status).toHaveTextContent(bookingCopy.checkOutSoon(HOLDER));

    const link = screen.getByRole('link', { name: bookingCopy.openCart(HOLDER) });
    expect(link).toHaveAttribute('href', CART_URL);
    expect(link).toHaveAttribute('target', '_blank');
    expect(screen.queryByRole('button', { name: new RegExp(bookingCopy.addToCart) })).toBeNull();
  });

  test('a held site is never offered a second hold, even once another one finished', () => {
    show({
      actions: cartActionsOf(
        { kind: 'held', cell: CELL, cartUrl: CART_URL, heldAt: HELD_AT },
        { kind: 'held', cell: ELSEWHERE, cartUrl: CART_URL, heldAt: HELD_AT },
      ),
    });

    expect(screen.queryByRole('button', { name: new RegExp(bookingCopy.addToCart) })).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent(bookingCopy.heldTitle());
  });

  test('a failed hold says so, by the reason the server gave, and offers a retry', () => {
    const onAddToCart = vi.fn();
    show({
      gate: { state: 'ready', onAddToCart },
      actions: cartActionsOf({
        kind: 'failed',
        cell: CELL,
        code: 'cart_not_added',
        holder: HOLDER,
      }),
    });

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(bookingCopy.holdFailed);
    expect(alert).toHaveTextContent(settingsErrorMessage('cart_not_added', HOLDER));
    expect(step()).toBeEnabled();

    fireEvent.click(step());
    expect(onAddToCart).toHaveBeenCalledTimes(1);
  });

  test('an answer about another cell is not read as this one', () => {
    show({
      actions: cartActionsOf({
        kind: 'held',
        cell: { campsiteId: '999', date: NIGHT },
        cartUrl: CART_URL,
        heldAt: HELD_AT,
      }),
    });

    expect(screen.queryByRole('status')).toBeNull();
    expect(step()).toBeEnabled();
  });

  test('Escape dismisses it and hands focus back to the cell that opened it', () => {
    const onClose = vi.fn();
    const anchor = anchorFor();
    show({ anchor, onClose });

    fireEvent.keyDown(screen.getByRole('group', { name: bookingCopy.addToCart }), {
      key: 'Escape',
    });

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(anchor);
  });

  test('it dismisses itself when the night it was anchored to leaves the grid', () => {
    const onClose = vi.fn();
    const anchor = document.createElement('button');
    show({ anchor, onClose });

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
