import { expect, test } from 'vitest';
import { apiError, toContractId } from './contractIds';

test('toContractId converts a safe bigint', () => {
  expect(toContractId(42n)).toBe(42);
  expect(toContractId(BigInt(Number.MAX_SAFE_INTEGER))).toBe(Number.MAX_SAFE_INTEGER);
});

test('toContractId throws above MAX_SAFE_INTEGER', () => {
  expect(() => toContractId(BigInt(Number.MAX_SAFE_INTEGER) + 1n)).toThrow(RangeError);
});

test('apiError answers the status with an error code body', async () => {
  const response = apiError(404, 'not_found');
  expect(response.status).toBe(404);
  expect(await response.json()).toEqual({ error: 'not_found' });
});
