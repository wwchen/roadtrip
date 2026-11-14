import type { AddToCartResponseDto } from '../../contract';
import { RECGOV_PROVIDER, stayWindow, type AddToCartCommand, type AddToCartOutcome, type RefusalCategory } from '../../service/booking/model';
import {
  errorAnswer,
  invalidBody,
  okAnswer,
  STATUS_BAD_GATEWAY,
  STATUS_BAD_REQUEST,
  STATUS_CONFLICT,
  STATUS_FORBIDDEN,
  STATUS_SERVICE_UNAVAILABLE,
  STATUS_UNPROCESSABLE,
  type HttpAnswer,
  type Parsed,
  withDetail,
} from './httpAnswer';

const INVALID_WINDOW = 'invalid_window';
const UNSUPPORTED_TARGET = 'unsupported_target';
const CREDENTIALS_REQUIRED = 'credentials_required';
const NOT_AVAILABLE = 'not_available';
const BOOKING_UNAVAILABLE = 'booking_unavailable';
const COMPLETED = 'completed';
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const DIGITS = /^\d+$/;
const STATUS_BY_CATEGORY: Readonly<Record<RefusalCategory, number>> = {
  caller_action: STATUS_FORBIDDEN,
  retry_later: STATUS_CONFLICT,
  upstream: STATUS_BAD_GATEWAY,
};

function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && ISO_DATE.test(value) && !Number.isNaN(Date.parse(value));
}

export function parseAddToCart(body: unknown): Parsed<AddToCartCommand> {
  if (typeof body !== 'object' || body === null) return invalidBody();
  const { campsite_id: rawId, start_date: startDate, end_date: endDate } = body as Record<string, unknown>;
  const idText = typeof rawId === 'number' && Number.isSafeInteger(rawId) ? String(rawId) : typeof rawId === 'string' ? rawId.trim() : '';
  if (!DIGITS.test(idText) || !isIsoDate(startDate) || !isIsoDate(endDate)) return invalidBody();
  const window = stayWindow(startDate, endDate);
  if (window === null) return { kind: 'invalid', answer: errorAnswer(STATUS_BAD_REQUEST, INVALID_WINDOW) };
  return { kind: 'ok', value: { campsiteId: BigInt(idText), window } };
}

function providerError(status: number, error: string, providerDisplay: string, detail?: string): HttpAnswer<never> {
  return {
    status,
    body: { error, ...withDetail(detail), provider: RECGOV_PROVIDER, provider_display: providerDisplay },
  };
}

export function addToCartAnswer(outcome: AddToCartOutcome): HttpAnswer<AddToCartResponseDto> {
  switch (outcome.kind) {
    case 'held':
      return okAnswer({ status: COMPLETED, cart_url: outcome.cartUrl, provider: RECGOV_PROVIDER, provider_display: outcome.providerDisplay });
    case 'unsupported_target':
      return errorAnswer(STATUS_UNPROCESSABLE, UNSUPPORTED_TARGET);
    case 'credentials_required':
      return providerError(STATUS_FORBIDDEN, CREDENTIALS_REQUIRED, outcome.providerDisplay);
    case 'not_available':
      return providerError(STATUS_CONFLICT, NOT_AVAILABLE, outcome.providerDisplay);
    case 'refused':
      return providerError(STATUS_BY_CATEGORY[outcome.refusal.category], outcome.refusal.code, outcome.providerDisplay, outcome.refusal.detail);
    case 'unavailable':
      return providerError(STATUS_SERVICE_UNAVAILABLE, BOOKING_UNAVAILABLE, outcome.providerDisplay);
  }
}
