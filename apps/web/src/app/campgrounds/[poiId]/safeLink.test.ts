import { expect, test } from 'vitest';
import { safeLink } from './safeLink';

test.each(['http://example.com/reserve', 'https://example.com/reserve?site=7#top', 'HTTPS://EXAMPLE.COM/'])(
  'safeLink passes %j through unchanged',
  (url) => {
    expect(safeLink(url)).toBe(url);
  },
);

test.each([
  'javascript:alert(1)',
  'JavaScript:alert(1)',
  ' javascript:alert(1)',
  'java\tscript:alert(1)',
  'data:text/html,<script>alert(1)</script>',
  'vbscript:msgbox(1)',
  'file:///etc/passwd',
  'ftp://example.com/x',
  'mailto:a@example.com',
  'blob:https://example.com/uuid',
])('safeLink rejects the non-http scheme %j', (url) => {
  expect(safeLink(url)).toBeNull();
});

test.each(['', 'not a url', '/relative/path', 'example.com/x', '//example.com/x', 'http://'])(
  'safeLink rejects the malformed, relative or protocol-relative %j',
  (url) => {
    expect(safeLink(url)).toBeNull();
  },
);
