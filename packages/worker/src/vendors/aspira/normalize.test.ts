import { describe, expect, test } from 'vitest';
import { jaccard, normalize, tokensOf } from './normalize.js';

describe('normalize, the key every geometry index and every leaf lookup goes through', () => {
  test.each([
    ['Two Jack Lakeside Campground', 'two jack lakeside'],
    ['Banff National Park of Canada', 'banff'],
    ['Pacific Rim National Park Reserve', 'pacific rim'],
    ['Fathom Five National Marine Park', 'fathom five'],
    ['Saguenay-St. Lawrence National Marine Conservation Area', 'saguenay st lawrence'],
    ['Fort Langley National Historic Site', 'fort langley'],
    ['Alice Lake Provincial Park', 'alice lake'],
    ['Deception Pass State Park', 'deception pass'],
    ['Mount Spokane State Recreation Area', 'mount spokane'],
    ['Capitol State Forest', 'capitol'],
    ['Riverside State Campground', 'riverside'],
    ['Lake Easton Recreation Area', 'lake easton'],
    ['Trailer Court Village', ''],
    ['Sunshine Cabins', 'sunshine'],
    ['Sunshine Cabin', 'sunshine'],
    ['Visitor Centre', 'visitor'],
    ['Visitor Center', 'visitor'],
    ['park', ''],
    ['  Alice   Lake  ', 'alice lake'],
    ["Alice's Lake", 'alice s lake'],
    ['Alice & Bob', 'alice bob'],
    ['Site 42', 'site 42'],
    ['', ''],
  ])('%j -> %j', (input, expected) => {
    expect(normalize(input)).toBe(expected);
  });

  test('a trailing and or or is dropped, but one in the middle is kept', () => {
    expect(normalize('Alice Lake and')).toBe('alice lake');
    expect(normalize('Alice Lake or')).toBe('alice lake');
    expect(normalize('Alice and Bob')).toBe('alice and bob');
    expect(normalize('Alice Lake and Campground')).toBe('alice lake');
    expect(normalize('Sandbank')).toBe('sandbank');
  });

  test('cruft is only stripped on a word boundary, so a name that merely contains it survives', () => {
    expect(normalize('Parkland')).toBe('parkland');
    expect(normalize('Centreville')).toBe('centreville');
    expect(normalize('Reserved Grove')).toBe('reserved grove');
  });

  test('the longer park phrases win over the shorter ones they contain', () => {
    expect(normalize('Gwaii Haanas National Park Reserve')).toBe('gwaii haanas');
    expect(normalize('Gwaii Haanas National Marine Conservation Area Reserve')).toBe('gwaii haanas');
  });

  test('non-ASCII collapses to a separator, so accents never leave a stray letter', () => {
    expect(normalize('Forêt Montmorency')).toBe('for t montmorency');
    expect(normalize('Σ-Σ')).toBe('');
  });
});

describe('jaccard token overlap', () => {
  test.each([
    [['a'], ['a'], 1],
    [['a', 'b'], ['a'], 0.5],
    [['a', 'b'], ['c'], 0],
    [['a', 'b'], ['b', 'a'], 1],
    [['alpha', 'lake'], ['alpha', 'lake', 'extra'], 2 / 3],
  ])('%j vs %j', (left, right, expected) => {
    expect(jaccard(new Set(left), new Set(right))).toBe(expected);
  });

  test('an empty side scores nothing rather than dividing by zero', () => {
    expect(jaccard(new Set(), new Set(['a']))).toBe(0);
    expect(jaccard(new Set(['a']), new Set())).toBe(0);
    expect(jaccard(new Set(), new Set())).toBe(0);
  });

  test('tokens are split on single spaces, so an empty key is one empty token', () => {
    expect([...tokensOf('alpha lake')]).toEqual(['alpha', 'lake']);
    expect([...tokensOf('')]).toEqual(['']);
    expect(jaccard(tokensOf(''), tokensOf(''))).toBe(1);
  });
});
