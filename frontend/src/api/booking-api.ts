// Direct add-to-cart, from the availability grid.
//
// A deliberately slow call: a real browser drives recreation.gov behind it, so
// tens of seconds is a normal success, not a hang. No timeout is set here — the
// backend already budgets the companion, and aborting locally would leave a
// hold that may well have succeeded with nothing watching it.

import { jsonPostOk, type RequestOptions } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';

export interface AddToCartFields {
  campsite_id: number | string;
  start_date: string;
  end_date: string;
}

export interface AddToCartResponse {
  status: 'completed';
  /** Where the held site is. Shown to the user; they finish checkout there. */
  cart_url: string;
}

/**
 * Holds one campsite-night range in the caller's own rec.gov cart.
 *
 * Throws `HttpError` with `code` set to the backend's own reason —
 * `credentials_required`, `not_available`, `profile_busy`,
 * `recgov_session_expired`, `companion_unavailable`, … — which is what the UI
 * maps to copy.
 */
export function addToCart(
  fields: AddToCartFields,
  options: RequestOptions = {},
): Promise<AddToCartResponse> {
  return jsonPostOk<AddToCartResponse>(ADD_TO_CART_URL, fields, options);
}
