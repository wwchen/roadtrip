import type {
  AddToCartRequestDto,
  AddToCartResponseDto,
  ApiErrorSchema,
} from './generated/api-types';
import { jsonPostOk, type RequestOptions } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';

const SECONDS_IN_MS = 1_000;

export const CART_HOLD_DEADLINE_MS = 240 * SECONDS_IN_MS;

export const HOLD_UNANSWERED = 'hold_unanswered';

export class CartHoldUnanswered extends Error {
  readonly code = HOLD_UNANSWERED;

  constructor() {
    super(`add-to-cart: no answer within ${CART_HOLD_DEADLINE_MS}ms`);
    this.name = 'CartHoldUnanswered';
  }
}

export type AddToCartFields = AddToCartRequestDto;

export type AddToCartResponse = AddToCartResponseDto;

export type AddToCartFailure = ApiErrorSchema;

const UNKNOWN_ERROR = '';

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
    error: typeof carried?.code === 'string' ? carried.code : (carried?.error ?? UNKNOWN_ERROR),
    provider: carried?.provider,
    provider_display: carried?.providerDisplay ?? carried?.provider_display,
  };
}

export async function addToCart(
  fields: AddToCartFields,
  { signal }: RequestOptions = {},
): Promise<AddToCartResponse> {
  const deadline = new AbortController();
  const timer = setTimeout(() => deadline.abort(), CART_HOLD_DEADLINE_MS);
  try {
    return await jsonPostOk<AddToCartResponse>(ADD_TO_CART_URL, fields, {
      signal: signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal,
    });
  } catch (err) {
    throw deadline.signal.aborted ? new CartHoldUnanswered() : err;
  } finally {
    clearTimeout(timer);
  }
}
