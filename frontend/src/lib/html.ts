// HTML/text formatting helpers. Typed port of the string section of
// web/core.js — behavior preserved exactly.
//
// `escapeHtml` and `callButtonsHTML` build markup strings, which React normally
// makes unnecessary. They are ported rather than dropped because the Phase-4
// map still renders MapLibre popups and the campground card through
// `setHTML`/`innerHTML`, which are outside React's escaping. Phase 4 replaces
// `callButtonsHTML` with a component; until then this is the escaped path.

const HTML_ESCAPES: Readonly<Record<string, string>> = Object.freeze({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
});

const DEFAULT_CALL_BUTTON_CLASS = 'cg-btn cg-btn-tertiary';

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
 * Render one or more `Call …` tertiary buttons from a phone field that may be a
 * single number, slash-delimited, or comma-delimited (e.g.
 * `"530.336.5521/530.257.2151"` → two buttons).
 *
 * `btnClass` is interpolated unescaped, matching the original: it is a
 * call-site constant, never user or upstream data. Both the visible number and
 * the `tel:` href ARE escaped, because those come from provider data.
 */
export function callButtonsHTML(
  phoneRaw: string | null | undefined,
  btnClass: string = DEFAULT_CALL_BUTTON_CLASS,
): string {
  if (!phoneRaw) return '';
  const numbers = String(phoneRaw)
    .split(PHONE_SEPARATORS)
    .map((s) => s.trim())
    .filter(Boolean);
  return numbers
    .map((n) => {
      const digits = n.replace(NON_DIAL_CHARS, '');
      const safe = escapeHtml(formatPhone(n));
      return `<a class="${btnClass}" href="tel:${escapeHtml(digits)}">Call ${safe}</a>`;
    })
    .join('');
}
