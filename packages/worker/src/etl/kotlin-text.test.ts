import { describe, expect, test } from 'vitest';
import { isKotlinBlank, kotlinEqualsIgnoreCase, kotlinTrim, nonBlank } from './kotlin-text.js';

const NBSP = '\u00a0';
const FIGURE_SPACE = '\u2007';
const NARROW_NBSP = '\u202f';
const FILE_SEPARATOR = '\u001c';
const GROUP_SEPARATOR = '\u001d';
const RECORD_SEPARATOR = '\u001e';
const UNIT_SEPARATOR = '\u001f';
const LINE_SEPARATOR = '\u2028';
const PARAGRAPH_SEPARATOR = '\u2029';
const IDEOGRAPHIC_SPACE = '\u3000';
const BYTE_ORDER_MARK = '\ufeff';
const NEXT_LINE = '\u0085';
const ZERO_WIDTH_SPACE = '\u200b';

const KOTLIN_WHITESPACE: [string, string][] = [
  ['no-break space', NBSP],
  ['figure space', FIGURE_SPACE],
  ['narrow no-break space', NARROW_NBSP],
  ['file separator', FILE_SEPARATOR],
  ['group separator', GROUP_SEPARATOR],
  ['record separator', RECORD_SEPARATOR],
  ['unit separator', UNIT_SEPARATOR],
  ['line separator', LINE_SEPARATOR],
  ['paragraph separator', PARAGRAPH_SEPARATOR],
  ['ideographic space', IDEOGRAPHIC_SPACE],
];

describe('kotlinTrim', () => {
  test.each(KOTLIN_WHITESPACE)('strips a %s from the front and from the back, but not from the middle', (_name, space) => {
    expect(kotlinTrim(`${space}a`)).toBe('a');
    expect(kotlinTrim(`a${space}`)).toBe('a');
    expect(kotlinTrim(`a${space}b`)).toBe(`a${space}b`);
  });

  test('keeps a byte order mark at either end, which JavaScript trim() would strip', () => {
    expect(kotlinTrim(`${BYTE_ORDER_MARK}a${BYTE_ORDER_MARK}`)).toBe(`${BYTE_ORDER_MARK}a${BYTE_ORDER_MARK}`);
  });
});

describe('isKotlinBlank', () => {
  test.each([
    ['empty', ''],
    ['ASCII whitespace', ' \t\r\n\v\f'],
    ['a no-break space, which Character.isSpaceChar adds to isWhitespace', NBSP],
    ['a figure space', FIGURE_SPACE],
    ['a narrow no-break space', NARROW_NBSP],
    ['a file separator, which JavaScript trim() keeps', FILE_SEPARATOR],
    ['a group separator', GROUP_SEPARATOR],
    ['a record separator', RECORD_SEPARATOR],
    ['a unit separator', UNIT_SEPARATOR],
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

const LATIN_CAPITAL_I_WITH_DOT_ABOVE = String.fromCodePoint(0x130);
const LATIN_SMALL_DOTLESS_I = String.fromCodePoint(0x131);
const LATIN_SMALL_LONG_S = String.fromCodePoint(0x17f);
const KELVIN_SIGN = String.fromCodePoint(0x212a);
const LATIN_SMALL_SHARP_S = String.fromCodePoint(0xdf);

describe('kotlinEqualsIgnoreCase, Java String.equalsIgnoreCase against an ASCII target', () => {
  test.each([
    ['true', 'true'],
    ['TRUE', 'true'],
    ['tRuE', 'true'],
    ['Image', 'Image'],
    ['IMAGE', 'Image'],
    ['image', 'Image'],
    ['official web site', 'Official Web Site'],
  ])('%j equals %j', (value, target) => {
    expect(kotlinEqualsIgnoreCase(value, target)).toBe(true);
  });

  test.each([
    ['', 'true'],
    ['tru', 'true'],
    ['truee', 'true'],
    ['yes', 'true'],
    ['true ', 'true'],
    [' true', 'true'],
    ['Imagf', 'Image'],
  ])('%j does not equal %j', (value, target) => {
    expect(kotlinEqualsIgnoreCase(value, target)).toBe(false);
  });

  test('null never equals a text', () => {
    expect(kotlinEqualsIgnoreCase(null, 'true')).toBe(false);
    expect(kotlinEqualsIgnoreCase(undefined, 'true')).toBe(false);
  });

  test.each([
    ['a capital I with a dot above, which Java lower-cases to i', LATIN_CAPITAL_I_WITH_DOT_ABOVE, 'i'],
    ['a dotless i, which Java upper-cases to I', LATIN_SMALL_DOTLESS_I, 'I'],
    ['a long s, which Java upper-cases to S', LATIN_SMALL_LONG_S, 'S'],
    ['a Kelvin sign, which Java lower-cases to k', KELVIN_SIGN, 'k'],
    ['a long s inside a word', `official web ${LATIN_SMALL_LONG_S}ite`, 'Official Web Site'],
    ['a capital I with a dot above inside a word', `${LATIN_CAPITAL_I_WITH_DOT_ABOVE}mage`, 'Image'],
  ])('%s equals its ASCII look-alike', (_label, value, target) => {
    expect(kotlinEqualsIgnoreCase(value, target)).toBe(true);
  });

  test('which JavaScript lower-casing alone would miss', () => {
    expect(LATIN_CAPITAL_I_WITH_DOT_ABOVE.toLowerCase()).not.toBe('i');
    expect(LATIN_SMALL_LONG_S.toLowerCase()).not.toBe('s');
  });

  test('a sharp s is not an s', () => {
    expect(kotlinEqualsIgnoreCase(LATIN_SMALL_SHARP_S, 's')).toBe(false);
    expect(kotlinEqualsIgnoreCase(`${LATIN_SMALL_SHARP_S}`, 'ss')).toBe(false);
  });
});
