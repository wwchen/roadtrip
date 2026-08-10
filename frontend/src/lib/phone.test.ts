import { describe, expect, test } from 'vitest';
import { formatPhone, phoneNumbers, telHref } from './phone';

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
// depends on exactly these rules. The one assertion that did NOT survive is the
// escaping of provider data into markup — React escapes what it renders, so there
// is no longer any hand-built markup here to escape into.
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
