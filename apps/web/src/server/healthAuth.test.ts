import { expect, test } from 'vitest';
import { isAuthorizedForDatabaseCheck } from './healthAuth';

const SECRET = 'a-configured-secret';

test('the exact secret is authorized', () => {
  expect(isAuthorizedForDatabaseCheck(`Bearer ${SECRET}`, SECRET)).toBe(true);
});

test.each([`Bearer ${SECRET}x`, `Bearer ${SECRET.slice(1)}`, 'Bearer other', SECRET, `Token ${SECRET}`, ''])(
  'the header %j is not authorized',
  (header) => {
    expect(isAuthorizedForDatabaseCheck(header, SECRET)).toBe(false);
  },
);

test.each([undefined, '', ' ', '\t\n'])('an unusable configured secret %j authorizes nothing', (configured) => {
  expect(isAuthorizedForDatabaseCheck(`Bearer ${configured ?? ''}`, configured)).toBe(false);
  expect(isAuthorizedForDatabaseCheck('Bearer anything', configured)).toBe(false);
  expect(isAuthorizedForDatabaseCheck('Bearer  ', configured)).toBe(false);
});

test('a secret with surrounding whitespace matches only exactly, never trimmed', () => {
  expect(isAuthorizedForDatabaseCheck('Bearer  padded ', ' padded ')).toBe(true);
  expect(isAuthorizedForDatabaseCheck('Bearer padded', ' padded ')).toBe(false);
  expect(isAuthorizedForDatabaseCheck('Bearer  padded ', 'padded')).toBe(false);
});
