import type {
  AddToCartRequestDto,
  AddToCartResponseDto,
  ApiErrorSchema,
} from './generated/api-types';
import { jsonPostOk, type RequestOptions } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';

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

export function addToCart(
  fields: AddToCartFields,
  options: RequestOptions = {},
): Promise<AddToCartResponse> {
  return jsonPostOk<AddToCartResponse>(ADD_TO_CART_URL, fields, options);
}
