import { useCallback, useMemo, useReducer, useRef } from 'react';
import { signIn } from '@/api/auth-api';
import { addToCart, addToCartFailure } from '@/api/booking-api';
import {
  nextCartAction,
  type CartAction,
  type CartActionCell,
} from '@/domain/place/cart-action';
import { NO_CART_GATE, type CartGate } from '@/domain/place/cart-gate';
import type { SettingsTab } from '@/lib/settings-tabs';
import { stayEndDate, type WatchCapabilities } from '@/lib/watch-windows';
import { useSettingsStore } from '@/stores/settingsStore';

const BOOKING_TAB: SettingsTab = 'booking';

const IDLE: CartAction | null = null;

export interface PlaceCart {
  gate: CartGate;
  action: CartAction | null;
}

export function usePlaceCart(capabilities: WatchCapabilities): PlaceCart {
  const [action, dispatch] = useReducer(nextCartAction, IDLE);
  const openSettings = useSettingsStore((state) => state.openSettings);
  const inFlight = useRef(false);

  const hold = useCallback((campsiteId: string, date: string) => {
    if (inFlight.current) return;
    inFlight.current = true;
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
          ...(answer.provider_display ? { holder: answer.provider_display } : null),
        });
      })
      .catch((err: unknown) => {
        const { error: code, provider_display: holder } = addToCartFailure(err);
        dispatch({ type: 'failed', cell, code, ...(holder ? { holder } : null) });
      })
      .finally(() => {
        inFlight.current = false;
      });
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
      default:
        return NO_CART_GATE;
    }
  }, [capabilities.addToCart, capabilities.addToCartProviderDisplay, hold, toSettings]);

  return { gate, action };
}
