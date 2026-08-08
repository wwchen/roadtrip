import { describe, expect, test } from 'vitest';
import { callButtonsHTML, escapeHtml, formatPhone } from './html';

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

describe('callButtonsHTML', () => {
  test('renders one button for a single number', () => {
    expect(callButtonsHTML('530.336.5521')).toBe(
      '<a class="cg-btn cg-btn-tertiary" href="tel:5303365521">Call (530) 336-5521</a>',
    );
  });

  test('splits slash-delimited numbers into two buttons', () => {
    const html = callButtonsHTML('530.336.5521/530.257.2151');
    expect(html).toBe(
      '<a class="cg-btn cg-btn-tertiary" href="tel:5303365521">Call (530) 336-5521</a>' +
        '<a class="cg-btn cg-btn-tertiary" href="tel:5302572151">Call (530) 257-2151</a>',
    );
  });

  test.each([[','], [';'], ['/']])('splits on %j', (separator) => {
    expect(callButtonsHTML(`5303365521${separator}5302572151`).match(/<a /g)).toHaveLength(2);
  });

  test('keeps a leading + in the tel: href but drops other punctuation', () => {
    expect(callButtonsHTML('+44 20 7123 4567')).toContain('href="tel:+442071234567"');
  });

  test('trims whitespace and drops empty segments', () => {
    expect(callButtonsHTML(' 5303365521 , ,5302572151 ').match(/<a /g)).toHaveLength(2);
  });

  test.each([[''], [null], [undefined]])('renders nothing for %j', (value) => {
    expect(callButtonsHTML(value)).toBe('');
  });

  test('escapes provider data in both the label and the href', () => {
    const html = callButtonsHTML('"><script>alert(1)</script>');
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });

  test('honours a custom button class', () => {
    expect(callButtonsHTML('5303365521', 'lds-button')).toContain('class="lds-button"');
  });
});
