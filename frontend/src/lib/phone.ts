// Phone parsing and formatting. Typed port of the phone section of web/core.js —
// behaviour preserved exactly. `features/drawer/parts.tsx`'s `CallButtons` is the
// consumer; these three are the shared rule it renders from.
//
// Was `lib/html.ts`, and carried two markup builders that Phase 5 took with `web/`:
//
//   - `callButtonsHTML` built `<a class="cg-btn …">Call …</a>` strings for the
//     vanilla drawer. `CallButtons` renders the same thing as components, so once
//     the vanilla tree was gone the string builder had no caller.
//   - `escapeHtml` never had one at all: `git log -S` finds no non-test importer in
//     `frontend/src` since Phase 0 ported it. It was ported for the MapLibre popups
//     and the campground card, and 4b/4c built both as components instead. Meanwhile
//     `format.ts` recorded why it should not exist ("React escapes text nodes itself.
//     Porting it would invite double-escaping"), and the one place markup IS built by
//     hand — `descriptionHtml` in `upstream-html.ts` — escapes *by construction*,
//     setting text nodes and reading back `outerHTML` precisely so there is no
//     hand-rolled escaper to get wrong. Its tests went with it; `upstream-html`'s own
//     suite covers the sanitiser that actually guards `dangerouslySetInnerHTML`.
//
// Which left a file named `html.ts` containing no HTML. Hence the rename.

/** Phone fields arrive single, or slash/comma/semicolon-delimited. */
const PHONE_SEPARATORS = /[/,;]/;
/** `tel:` hrefs keep digits and a leading `+` only. */
const NON_DIAL_CHARS = /[^\d+]/g;

const US_NATIONAL_DIGITS = 10;
const US_COUNTRY_CODE_DIGITS = 11;
const US_COUNTRY_CODE = '1';

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
