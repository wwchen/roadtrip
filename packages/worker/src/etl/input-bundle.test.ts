import { describe, expect, test } from 'vitest';
import type { Envelope } from './envelope.js';
import { InputBundle } from './input-bundle.js';

function envelopeNamed(part: string): Envelope {
  return {
    fetcher: 'fetch_fixture',
    fetcherVersion: '1',
    fetchedAt: '2026-06-07T21:33:18Z',
    request: {},
    response: {},
    pollerRunId: null,
    payload: null,
    part,
  };
}

const e1 = envelopeNamed('one');
const e2 = envelopeNamed('two');

describe('InputBundle', () => {
  test('envelope(slug) rejects a multi-part input and soleEnvelopes rejects two inputs', () => {
    const bundle = new InputBundle(
      new Map([
        ['a', [e1, e2]],
        ['b', [e1]],
      ]),
    );
    expect(() => bundle.envelope('a')).toThrow(/multi-part \(2 pages\)/);
    expect(() => bundle.soleEnvelopes()).toThrow(/exactly one data_source input; got 2/);
    expect(() => bundle.envelopes('c')).toThrow(/input 'c' is not a declared data_source input/);
  });

  test('envelope(slug) returns the single envelope and envelopes(slug) the whole capture', () => {
    const bundle = new InputBundle(
      new Map([
        ['a', [e1, e2]],
        ['b', [e1]],
      ]),
    );
    expect(bundle.envelope('b')).toBe(e1);
    expect(bundle.envelopes('a')).toEqual([e1, e2]);
  });

  test('an input with no envelopes is not single', () => {
    const bundle = new InputBundle(new Map([['a', []]]));
    expect(() => bundle.envelope('a')).toThrow(/multi-part \(0 pages\)/);
  });

  test('soleEnvelopes returns the one input', () => {
    expect(new InputBundle(new Map([['only', [e1, e2]]])).soleEnvelopes()).toEqual([e1, e2]);
  });

  test('soleEnvelopes rejects zero inputs', () => {
    expect(() => new InputBundle(new Map()).soleEnvelopes()).toThrow(/exactly one data_source input; got 0/);
  });

  test('dataSourceSlugs keeps declaration order rather than sorting', () => {
    const bundle = new InputBundle(
      new Map([
        ['zeta', [e1]],
        ['alpha', [e1]],
        ['mid', [e1]],
      ]),
    );
    expect(bundle.dataSourceSlugs()).toEqual(['zeta', 'alpha', 'mid']);
  });
});
