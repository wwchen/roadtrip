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
 * Split a phone field into individual numbers.
 *
 * Provider data puts several numbers in one string, delimited by a slash, comma or
 * semicolon (`"530.336.5521/530.257.2151"` → two). Exported because both renderers
 * need the same rule: the HTML-string builder below and the React `CallButtons` in
 * `features/drawer/parts.tsx`.
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

/**
 * Render one or more `Call …` tertiary buttons from a phone field.
 *
 * `btnClass` is interpolated unescaped, matching the original: it is a
 * call-site constant, never user or upstream data. Both the visible number and
 * the `tel:` href ARE escaped, because those come from provider data.
 *
 * The React drawer builds the same buttons as components instead; this stays for
 * the vanilla tree and goes with it in Phase 5.
 */
export function callButtonsHTML(
  phoneRaw: string | null | undefined,
  btnClass: string = DEFAULT_CALL_BUTTON_CLASS,
): string {
  return phoneNumbers(phoneRaw)
    .map((n) => {
      const safe = escapeHtml(formatPhone(n));
      return `<a class="${btnClass}" href="${escapeHtml(telHref(n))}">Call ${safe}</a>`;
    })
    .join('');
}
