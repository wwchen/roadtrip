// Direct add-to-cart, from the availability grid.
//
// A deliberately slow call: a real browser drives the vendor behind it, so tens
// of seconds is a normal success. No local timeout — aborting would leave a hold
// that may well have succeeded with nothing watching it.

import { jsonPostOk, type RequestOptions } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';

export interface AddToCartFields {
  /** Numeric on the wire. The backend's DTO is a Long, and the grid carries ids
   *  as strings — `Number()` at the call site, as `useWatches` does. */
  campsite_id: number;
  start_date: string;
  end_date: string;
}

export interface AddToCartResponse {
  status: 'completed';
  /** Where the held site is. Shown to the user; they finish checkout there. */
  cart_url: string;
  /** The provider id whose cart it is — not always the one serving availability. */
  provider: string;
}

/**
 * Holds one campsite-night range in the caller's own cart at the booking vendor.
 * Throws `HttpError` with `code` set to the backend's own reason, which is what
 * `settings-errors.ts` maps to copy.
 */
export function addToCart(
  fields: AddToCartFields,
  options: RequestOptions = {},
): Promise<AddToCartResponse> {
  return jsonPostOk<AddToCartResponse>(ADD_TO_CART_URL, fields, options);
}
