import { describe, expect, test } from 'vitest';
import { readMagicLink } from './magicLink';

describe('readMagicLink', () => {
  test('reads both halves of a magic link', () => {
    expect(readMagicLink('?watch=7&t=abc123')).toEqual({
      watchId: '7',
      token: 'abc123',
      stopOnArrival: false,
    });
  });

  test('ignores the other params an email link may carry', () => {
    expect(readMagicLink('?utm_source=mail&watch=7&t=abc123')).toEqual({
      watchId: '7',
      token: 'abc123',
      stopOnArrival: false,
    });
  });

  test('is null without a token, so a signed-in deep link is not mistaken for one', () => {
    expect(readMagicLink('?watch=7')).toBeNull();
    expect(readMagicLink('?action=modify&id=7')).toBeNull();
  });

  test('is null without a watch id, since a token alone has nothing to act on', () => {
    expect(readMagicLink('?t=abc123')).toBeNull();
  });

  test('is null on an empty query string', () => {
    expect(readMagicLink('')).toBeNull();
  });

  test('treats an empty value as absent', () => {
    expect(readMagicLink('?watch=7&t=')).toBeNull();
    expect(readMagicLink('?watch=&t=abc123')).toBeNull();
  });

  test("flags the email's Stop watch link", () => {
    expect(readMagicLink('?watch=7&t=abc&action=stop')?.stopOnArrival).toBe(true);
  });

  test('does not flag any other action, so a typo cannot stop a watch', () => {
    expect(readMagicLink('?watch=7&t=abc&action=stopp')?.stopOnArrival).toBe(false);
    expect(readMagicLink('?watch=7&t=abc&action=modify')?.stopOnArrival).toBe(false);
  });
});
