import { describe, expect, test } from 'vitest';
import { isKotlinBlank, nonBlank } from './kotlin-text.js';

const NBSP = ' ';
const FIGURE_SPACE = ' ';
const NARROW_NBSP = ' ';
const FILE_SEPARATOR = '\u001c';
const LINE_SEPARATOR = ' ';
const PARAGRAPH_SEPARATOR = ' ';
const IDEOGRAPHIC_SPACE = '　';
const BYTE_ORDER_MARK = '﻿';
const NEXT_LINE = '\u0085';
const ZERO_WIDTH_SPACE = '​';

describe('isKotlinBlank', () => {
  test.each([
    ['empty', ''],
    ['ASCII whitespace', ' \t\r\n\v\f'],
    ['a no-break space, which Character.isSpaceChar adds to isWhitespace', NBSP],
    ['a figure space', FIGURE_SPACE],
    ['a narrow no-break space', NARROW_NBSP],
    ['a file separator, which JavaScript trim() keeps', FILE_SEPARATOR],
    ['a line separator', LINE_SEPARATOR],
    ['a paragraph separator', PARAGRAPH_SEPARATOR],
    ['an ideographic space', IDEOGRAPHIC_SPACE],
  ])('%s is blank', (_label, value) => {
    expect(isKotlinBlank(value)).toBe(true);
  });

  test.each([
    ['a byte order mark, which JavaScript trim() strips', BYTE_ORDER_MARK],
    ['a next-line control', NEXT_LINE],
    ['a zero width space', ZERO_WIDTH_SPACE],
    ['text', 'a'],
    ['text between whitespace', ` ${NBSP}a${FILE_SEPARATOR} `],
    ['whitespace around a byte order mark', ` ${BYTE_ORDER_MARK} `],
  ])('%s is not blank', (_label, value) => {
    expect(isKotlinBlank(value)).toBe(false);
  });
});

describe('nonBlank', () => {
  test('returns the value untrimmed when it has any non-whitespace character', () => {
    expect(nonBlank(` ${NBSP}Planet Fitness `)).toBe(` ${NBSP}Planet Fitness `);
    expect(nonBlank(BYTE_ORDER_MARK)).toBe(BYTE_ORDER_MARK);
  });

  test('returns null for absent, empty and whitespace-only values', () => {
    expect(nonBlank(null)).toBeNull();
    expect(nonBlank(undefined)).toBeNull();
    expect(nonBlank('')).toBeNull();
    expect(nonBlank(`${NBSP}${FILE_SEPARATOR}`)).toBeNull();
  });
});
