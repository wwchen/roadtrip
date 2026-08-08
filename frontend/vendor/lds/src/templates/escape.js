// Text going into an HTML string has to be escaped, and it has to be escaped
// the SAME WAY React escapes it — otherwise the two bindings emit different
// bytes for the same input and the parity test is measuring the escaper rather
// than the markup.
//
// This is React's set: & < > " ', with the apostrophe as the numeric entity
// React uses rather than &apos;.
const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#x27;' };

/** Escapes a value for use as HTML text or inside a double-quoted attribute. */
export function escapeHtml(value) {
  if (value === null || value === undefined || value === false) return '';
  return String(value).replace(/[&<>"']/g, (c) => ESCAPES[c]);
}

/**
 * Marks a string as already-safe HTML, so a slot can take composed markup
 * rather than text. The wrapper is what makes the distinction explicit — an
 * unmarked string is always treated as text and escaped, so the unsafe path has
 * to be chosen deliberately rather than reached by forgetting.
 */
export function raw(html) {
  return { __html: String(html ?? '') };
}

/** Resolves a slot: raw() markup passes through, anything else is escaped text. */
export function slot(value) {
  if (value === null || value === undefined || value === false) return '';
  // A list of children resolves piecewise, so composing several things into one
  // slot does not need a wrapper element that would change the layout.
  if (Array.isArray(value)) return value.map(slot).join('');
  if (typeof value === 'object' && typeof value.__html === 'string') return value.__html;
  return escapeHtml(value);
}
