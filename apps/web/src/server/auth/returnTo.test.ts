import { expect, test } from 'vitest';
import { HOME_PATH, safeReturnTo, withReturnTo } from './returnTo';

test.each(['/', '/watches?action=new#form', '/availability?tab=runs'])('%s is kept', (path) => {
  expect(safeReturnTo(path)).toBe(path);
});

test.each([
  '//evil.example',
  '/\\evil.example',
  'https://evil.example/',
  'javascript:alert(1)',
  '%2F%2Fevil.example',
  '/%2F/evil.example',
  '/ok\u0000',
  '',
  null,
  undefined,
])('%j falls back to /', (raw) => {
  expect(safeReturnTo(raw)).toBe(HOME_PATH);
});

test('withReturnTo carries the path as one encoded parameter', () => {
  expect(withReturnTo('/auth/sign-in', '/watches?x=1')).toBe('/auth/sign-in?return_to=%2Fwatches%3Fx%3D1');
});
