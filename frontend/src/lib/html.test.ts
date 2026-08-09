import { describe, expect, test } from 'vitest';
import { escapeHtml, formatPhone, phoneNumbers, telHref } from './html';

describe('escapeHtml', () => {
  test('escapes all five characters', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;');
  });

  test('neutralises an injected tag and attribute break-out', () => {
    expect(escapeHtml('"><script>alert(1)</script>')).toBe(
      '&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;',
    );
  });

  test('escapes the ampersand of an existing entity, so it is not double-decoded', () => {
    expect(escapeHtml('&amp;')).toBe('&amp;amp;');
  });

  test('leaves plain text alone', () => {
    expect(escapeHtml('Lassen Volcanic')).toBe('Lassen Volcanic');
  });

  test('stringifies non-strings', () => {
    expect(escapeHtml(42)).toBe('42');
    expect(escapeHtml(null)).toBe('null');
    expect(escapeHtml(undefined)).toBe('undefined');
  });
});

describe('formatPhone', () => {
  test.each([
    ['5303365521', '(530) 336-5521'],
    ['530.336.5521', '(530) 336-5521'],
    ['(530) 336-5521', '(530) 336-5521'],
    ['15303365521', '(530) 336-5521'],
    ['+1 530-336-5521', '(530) 336-5521'],
  ])('%s formats as %s', (input, expected) => {
    expect(formatPhone(input)).toBe(expected);
  });

  test.each([
    ['555-1234'],
    ['+44 20 7123 4567'],
    ['25303365521'],
    ['ranger station'],
    [''],
  ])('passes %j through unchanged', (input) => {
    expect(formatPhone(input)).toBe(input);
  });
});

// These two carried no tests of their own: every rule below was asserted through
// `callButtonsHTML`, the HTML-string builder that went with `web/`. Restated
// directly rather than dropped, since `CallButtons` in `features/drawer/parts.tsx`
// now depends on exactly these rules. The one assertion that did NOT survive was
// the escaping of provider data into markup — React escapes what it renders, and
// `escapeHtml` has its own block above.
describe('phoneNumbers', () => {
  test('reads a single number as one entry', () => {
    expect(phoneNumbers('530.336.5521')).toEqual(['530.336.5521']);
  });

  test.each([[','], [';'], ['/']])('splits on %j', (separator) => {
    expect(phoneNumbers(`5303365521${separator}5302572151`)).toEqual([
      '5303365521',
      '5302572151',
    ]);
  });

  test('trims whitespace and drops empty segments', () => {
    expect(phoneNumbers(' 5303365521 , ,5302572151 ')).toEqual(['5303365521', '5302572151']);
  });

  test.each([[''], [null], [undefined]])('reads %j as no numbers', (value) => {
    expect(phoneNumbers(value)).toEqual([]);
  });
});

describe('telHref', () => {
  test('drops formatting punctuation', () => {
    expect(telHref('(530) 336-5521')).toBe('tel:5303365521');
  });

  test('keeps a leading + but drops other punctuation', () => {
    expect(telHref('+44 20 7123 4567')).toBe('tel:+442071234567');
  });
});
