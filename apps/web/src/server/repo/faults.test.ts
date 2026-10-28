import { expect, test } from 'vitest';
import {
  GEOMETRY_PARSE_FAULTS,
  TOPOLOGY_FAULT,
  causeChain,
  isGeometryParseFault,
  isTopologyFault,
} from './faults';

const WRAPPER_MESSAGE = 'Failed query';
const UNRELATED_MESSAGE = 'connection terminated';

const wrapped = (cause: string) => new Error(WRAPPER_MESSAGE, { cause: new Error(cause) });

test.each(GEOMETRY_PARSE_FAULTS)('a driver cause containing "%s" is a geometry parse fault', (message) => {
  expect(isGeometryParseFault(wrapped(`The coordinates ${message}`))).toBe(true);
  expect(isTopologyFault(wrapped(message))).toBe(false);
});

test('a driver cause naming a topology exception is a topology fault, not a parse fault', () => {
  const error = wrapped(`${TOPOLOGY_FAULT}: found non-noded intersection`);
  expect(isTopologyFault(error)).toBe(true);
  expect(isGeometryParseFault(error)).toBe(false);
});

test('an unrelated failure is neither fault', () => {
  const error = wrapped(UNRELATED_MESSAGE);
  expect(isTopologyFault(error)).toBe(false);
  expect(isGeometryParseFault(error)).toBe(false);
});

test('a self-referential cause chain terminates', () => {
  const error = new Error(WRAPPER_MESSAGE);
  error.cause = error;
  expect(causeChain(error)).toBe(`Error: ${WRAPPER_MESSAGE}`);
});
