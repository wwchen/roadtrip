import { useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useRef } from 'react';
import { signIn } from '@/api/auth-api';
import { addToCart, addToCartFailure } from '@/api/booking-api';
import {
  NO_CART_ACTIONS,
  cartActionFor,
  holdExpiresAt,
  nextCartActions,
  type CartActionCell,
  type CartActions,
} from '@/domain/place/cart-action';
import { NO_CART_GATE, type CartGate } from '@/domain/place/cart-gate';
import type { SettingsTab } from '@/lib/settings-tabs';
import { stayEndDate, type WatchCapabilities } from '@/lib/watch-windows';
import { useSettingsStore } from '@/stores/settingsStore';

const BOOKING_TAB: SettingsTab = 'booking';

const ALREADY_LAPSED_MS = 0;

export interface PlaceCart {
  gate: CartGate;
  actions: CartActions;
}

export function usePlaceCart(capabilities: WatchCapabilities): PlaceCart {
  const [actions, dispatch] = useReducer(nextCartActions, NO_CART_ACTIONS);
  const openSettings = useSettingsStore((state) => state.openSettings);
  const running = useRef(false);
  const standing = useRef<CartActions>(NO_CART_ACTIONS);
  const expiries = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  useLayoutEffect(() => {
    standing.current = actions;
  }, [actions]);

  const hold = useCallback((campsiteId: string, date: string) => {
    if (running.current) return;
    if (cartActionFor(standing.current, campsiteId, date)?.kind === 'held') return;
    running.current = true;
    const cell: CartActionCell = { campsiteId, date };
    dispatch({ type: 'requested', cell });
    void addToCart({
      campsite_id: Number(campsiteId),
      start_date: date,
      end_date: stayEndDate(date),
    })
      .then((answer) => {
        dispatch({
          type: 'held',
          cell,
          cartUrl: answer.cart_url,
          heldAt: Date.now(),
          ...(answer.provider_display ? { holder: answer.provider_display } : null),
        });
      })
      .catch((err: unknown) => {
        const { error: code, provider_display: holder } = addToCartFailure(err);
        dispatch({ type: 'failed', cell, code, ...(holder ? { holder } : null) });
      })
      .finally(() => {
        running.current = false;
      });
  }, []);

  useEffect(() => {
    const timers = expiries.current;
    for (const [key, action] of actions) {
      const expiresAt = holdExpiresAt(action);
      if (expiresAt === null || timers.has(key)) continue;
      const remaining = Math.max(ALREADY_LAPSED_MS, expiresAt - Date.now());
      timers.set(
        key,
        setTimeout(() => {
          timers.delete(key);
          dispatch({ type: 'expired', cell: action.cell });
        }, remaining),
      );
    }
    for (const [key, timer] of timers) {
      if (actions.get(key) === undefined) {
        clearTimeout(timer);
        timers.delete(key);
      }
    }
  }, [actions]);

  useEffect(() => {
    const timers = expiries.current;
    return () => {
      for (const timer of timers.values()) clearTimeout(timer);
      timers.clear();
    };
  }, []);

  const toSettings = useCallback(() => openSettings(BOOKING_TAB), [openSettings]);

  const gate = useMemo<CartGate>(() => {
    switch (capabilities.addToCart) {
      case 'ready':
        return { state: 'ready', onAddToCart: hold };
      case 'signed_out':
        return { state: 'signed_out', onSignIn: () => signIn() };
      case 'no_credentials':
        return {
          state: 'no_credentials',
          onOpenSettings: toSettings,
          ...(capabilities.addToCartProviderDisplay
            ? { holder: capabilities.addToCartProviderDisplay }
            : null),
        };
      case 'unsupported':
        return NO_CART_GATE;
    }
  }, [capabilities.addToCart, capabilities.addToCartProviderDisplay, hold, toSettings]);

  return { gate, actions };
}
