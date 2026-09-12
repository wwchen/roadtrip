// Direct add-to-cart, from the availability grid.
//
// A deliberately slow call: a real browser drives the vendor behind it, so tens
// of seconds is a normal success. No local timeout — aborting would leave a hold
// that may well have succeeded with nothing watching it.

import type {
  AddToCartRequestDto,
  AddToCartResponseDto,
  ApiErrorSchema,
} from './generated/api-types';
import { jsonPostOk, type RequestOptions } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';

/**
 * `campsite_id` is numeric on the wire. The backend's DTO is a Long, and the
 * grid carries ids as strings — `Number()` at the call site, as `useWatches` does.
 */
export type AddToCartFields = AddToCartRequestDto;

export type AddToCartResponse = AddToCartResponseDto;

/** The add-to-cart route's error envelope, as `HttpError` carries it. */
export type AddToCartFailure = ApiErrorSchema;

/** The envelope's `error` when the rejection carried none. */
const UNKNOWN_ERROR = '';

/**
 * Reads a rejected `addToCart` without every caller casting `unknown`.
 *
 * `HttpError` carries the envelope's `error` as `code` and its
 * `provider_display` camel-cased; a raw rejection may still carry the wire
 * spellings, so both are accepted.
 */
export function addToCartFailure(err: unknown): AddToCartFailure {
  const carried = err as
    | {
        code?: unknown;
        error?: string;
        provider?: string;
        providerDisplay?: string;
        provider_display?: string;
      }
    | null
    | undefined;
  return {
    // A `DOMException` carries a *numeric* legacy `code` (`AbortError` is 20),
    // which `settings-errors.ts` would render as "Something went wrong (20)".
    error: typeof carried?.code === 'string' ? carried.code : (carried?.error ?? UNKNOWN_ERROR),
    provider: carried?.provider,
    provider_display: carried?.providerDisplay ?? carried?.provider_display,
  };
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
