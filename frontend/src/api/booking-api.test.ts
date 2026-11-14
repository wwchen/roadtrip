import { describe, expect, test } from 'vitest';
import type { BookingActionStatus } from './generated/api-types';
import { addToCartFailure } from './booking-api';
import { HttpError } from './http';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';
const BAD_GATEWAY = 502;

describe('addToCartFailure', () => {
  test('reads the camelCased name an HttpError carries', () => {
    const err = new HttpError(ADD_TO_CART_URL, BAD_GATEWAY);
    err.code = 'cart_not_added';
    err.provider = 'recgov';
    err.providerDisplay = 'Recreation.gov';

    expect(addToCartFailure(err)).toEqual({
      error: 'cart_not_added',
      provider: 'recgov',
      provider_display: 'Recreation.gov',
    });
  });

  test('reads the raw wire spelling when a rejection carries it instead', () => {
    const raw = { error: 'credentials_required', provider: 'aspira', provider_display: 'BC Parks' };

    expect(addToCartFailure(raw)).toEqual({
      error: 'credentials_required',
      provider: 'aspira',
      provider_display: 'BC Parks',
    });
  });

  test('prefers the camelCased name when a rejection carries both', () => {
    const both = { providerDisplay: 'Recreation.gov', provider_display: 'stale' };

    expect(addToCartFailure(both).provider_display).toBe('Recreation.gov');
  });

  test('reads an unnamed refusal as undefined rather than throwing', () => {
    const err = new HttpError(ADD_TO_CART_URL, BAD_GATEWAY);
    err.code = 'unsupported_target';

    expect(addToCartFailure(err)).toEqual({
      error: 'unsupported_target',
      provider: undefined,
      provider_display: undefined,
    });
    expect(addToCartFailure(null).provider_display).toBeUndefined();
  });

  test('a DOMException numeric code is not read as a reason name', () => {
    const aborted = new DOMException('aborted', 'AbortError');

    expect(addToCartFailure(aborted).error).toBe('');
  });
});

function theOnlyStatusAHoldAnswersWith(status: BookingActionStatus): 'completed' {
  return status;
}

describe('what a 200 from the hold route can say', () => {
  test('is completed and nothing else, so the status code is the whole answer', () => {
    expect(theOnlyStatusAHoldAnswersWith('completed')).toBe('completed');
  });
});
