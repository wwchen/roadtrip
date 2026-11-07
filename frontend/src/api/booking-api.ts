import type {
  AddToCartRequestDto,
  AddToCartResponseDto,
  ApiErrorSchema,
} from './generated/api-types';
import { jsonPostOk } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';

const SECONDS_IN_MS = 1_000;

const COMPANION_RUN_BUDGET_MS = 180 * SECONDS_IN_MS;

const PREFLIGHT_SHARE_OF_RUN = 3;

const PREFLIGHT_STEPS = 2;

const SERVER_WORST_CASE_MS =
  COMPANION_RUN_BUDGET_MS + PREFLIGHT_STEPS * (COMPANION_RUN_BUDGET_MS / PREFLIGHT_SHARE_OF_RUN);

const DEADLINE_HEADROOM_MS = 30 * SECONDS_IN_MS;

export const CART_HOLD_DEADLINE_MS = SERVER_WORST_CASE_MS + DEADLINE_HEADROOM_MS;

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

export async function addToCart(fields: AddToCartFields): Promise<AddToCartResponse> {
  const deadline = new AbortController();
  const timer = setTimeout(() => deadline.abort(), CART_HOLD_DEADLINE_MS);
  try {
    return await jsonPostOk<AddToCartResponse>(ADD_TO_CART_URL, fields, {
      signal: deadline.signal,
    });
  } catch (err) {
    throw deadline.signal.aborted ? new CartHoldUnanswered() : err;
  } finally {
    clearTimeout(timer);
  }
}
