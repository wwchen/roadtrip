// Turning a person's name into a colour and a pair of letters.
//
// Both bindings need this and neither owns it, so it lives here rather than
// inside a component: the hue is DERIVED from the name, not stored, which is
// what makes the same person the same colour in every surface of every app
// without anyone persisting a colour. It resolves to one of the palette's nine
// hues and the avatar paints emph-soft on it, so contrast comes from the ramp
// rather than being checked per colour.
const HUES = ['red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'violet', 'pink', 'gray'];

/** The palette hue a name always resolves to. Stable across apps and reloads. */
export function hueForName(name) {
  const s = String(name || '');
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 1000003;
  return HUES[h % HUES.length];
}

/**
 * First and last initial — first only when the name is a single word.
 * Picks the first letter OR DIGIT of each word, so names that start with
 * punctuation or an emoji still produce something readable rather than a blank.
 */
export function initialsForName(name) {
  const words = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '';
  const letters = words.map((w) => (w.match(/\p{L}|\d/u) || [''])[0]);
  const picked = words.length === 1 ? letters[0] : letters[0] + letters[letters.length - 1];
  return picked.toUpperCase();
}
