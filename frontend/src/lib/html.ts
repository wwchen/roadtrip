// Phone/text formatting helpers. Typed port of the string section of
// web/core.js — behavior preserved exactly.
//
// `escapeHtml` stays because escaping is still needed outside React's own: the
// only `dangerouslySetInnerHTML` left in the tree is fed by
// `lib/upstream-html.ts`, whose whitelist sanitiser is built on it.
//
// `callButtonsHTML` is gone. It built `<a class="cg-btn …">Call …</a>` strings for
// the vanilla drawer; the React drawer renders `CallButtons` in
// `features/drawer/parts.tsx` from `phoneNumbers`/`telHref`/`formatPhone` below,
// so with `web/` deleted the string builder had no caller left. The three helpers
// are the shared rule both renderers were written against.

const HTML_ESCAPES: Readonly<Record<string, string>> = Object.freeze({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
});

/** Phone fields arrive single, or slash/comma/semicolon-delimited. */
const PHONE_SEPARATORS = /[/,;]/;
/** `tel:` hrefs keep digits and a leading `+` only. */
const NON_DIAL_CHARS = /[^\d+]/g;

const US_NATIONAL_DIGITS = 10;
const US_COUNTRY_CODE_DIGITS = 11;
const US_COUNTRY_CODE = '1';

export function escapeHtml(s: unknown): string {
  return String(s).replace(/[&<>"']/g, (c) => HTML_ESCAPES[c]!);
}

/** US 10-digit numbers → `(XXX) XXX-XXXX`; everything else passes through. */
export function formatPhone(s: string): string {
  const digits = String(s).replace(/\D/g, '');
  if (digits.length === US_NATIONAL_DIGITS) {
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  if (digits.length === US_COUNTRY_CODE_DIGITS && digits.startsWith(US_COUNTRY_CODE)) {
    return `(${digits.slice(1, 4)}) ${digits.slice(4, 7)}-${digits.slice(7)}`;
  }
  return s;
}

/**
 * Split a phone field into individual numbers.
 *
 * Provider data puts several numbers in one string, delimited by a slash, comma or
 * semicolon (`"530.336.5521/530.257.2151"` → two). Read by the React `CallButtons`
 * in `features/drawer/parts.tsx`, and by `telHref` below for each result.
 */
export function phoneNumbers(phoneRaw: string | null | undefined): string[] {
  if (!phoneRaw) return [];
  return String(phoneRaw)
    .split(PHONE_SEPARATORS)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** The `tel:` target for a number — digits and a leading `+` only. */
export function telHref(number: string): string {
  return `tel:${number.replace(NON_DIAL_CHARS, '')}`;
}
