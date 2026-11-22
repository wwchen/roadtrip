import { expect, test } from 'vitest';
import type { AddToCartOutcome } from '../../service/booking/model';
import { addToCartAnswer, parseAddToCart } from './addToCartHttp';

const DISPLAY = 'Recreation.gov';
const CART_URL = 'https://www.recreation.gov/cart';
const PROVIDER = { provider: 'recgov', provider_display: DISPLAY };
const BODY = { campsite_id: 55, start_date: '2026-12-10', end_date: '2026-12-11' };

test('a valid body parses to a command', () => {
  expect(parseAddToCart(BODY)).toEqual({ kind: 'ok', value: { campsiteId: 55n, window: { startDate: '2026-12-10', endDate: '2026-12-11' } } });
  expect(parseAddToCart({ ...BODY, campsite_id: ' 55 ' })).toMatchObject({ kind: 'ok', value: { campsiteId: 55n } });
});

test.each([[undefined], [null], ['x'], [{}], [{ ...BODY, campsite_id: 'x' }], [{ ...BODY, campsite_id: 1.5 }], [{ ...BODY, start_date: 'soon' }], [{ ...BODY, end_date: '2026-13-45' }]])(
  'a malformed body %j is 400 invalid_body',
  (body) => {
    expect(parseAddToCart(body)).toEqual({ kind: 'invalid', answer: { status: 400, body: { error: 'invalid_body' } } });
  },
);

test('an empty window is 400 invalid_window', () => {
  expect(parseAddToCart({ ...BODY, end_date: '2026-12-10' })).toEqual({ kind: 'invalid', answer: { status: 400, body: { error: 'invalid_window' } } });
});

const ANSWERS: [string, AddToCartOutcome, number, object][] = [
  ['held', { kind: 'held', cartUrl: CART_URL, providerDisplay: DISPLAY }, 200, { status: 'completed', cart_url: CART_URL, ...PROVIDER }],
  ['unsupported_target', { kind: 'unsupported_target' }, 422, { error: 'unsupported_target' }],
  ['credentials_required', { kind: 'credentials_required', providerDisplay: DISPLAY }, 403, { error: 'credentials_required', ...PROVIDER }],
  ['not_available', { kind: 'not_available', providerDisplay: DISPLAY }, 409, { error: 'not_available', ...PROVIDER }],
  [
    'refused caller_action',
    { kind: 'refused', refusal: { category: 'caller_action', code: 'c', detail: 'd' }, providerDisplay: DISPLAY },
    403,
    { error: 'c', detail: 'd', ...PROVIDER },
  ],
  ['refused retry_later', { kind: 'refused', refusal: { category: 'retry_later', code: 'c' }, providerDisplay: DISPLAY, cartUrl: CART_URL }, 409, { error: 'c', cart_url: CART_URL, ...PROVIDER }],
  ['refused upstream', { kind: 'refused', refusal: { category: 'upstream', code: 'c' }, providerDisplay: DISPLAY }, 502, { error: 'c', ...PROVIDER }],
  ['unavailable', { kind: 'unavailable', fault: 'unreachable', providerDisplay: DISPLAY }, 503, { error: 'relay_unreachable', ...PROVIDER }],
  ['unavailable, malformed', { kind: 'unavailable', fault: 'malformed', providerDisplay: DISPLAY }, 503, { error: 'relay_malformed', ...PROVIDER }],
];

test.each(ANSWERS)('%s answers its status and body', (_name, outcome, status, body) => {
  expect(addToCartAnswer(outcome)).toEqual({ status, body });
});
