// Open Icons — the JS surface over the sprite.
//
// The sprite itself is the product; this module exists so an app can resolve its
// URL through the bundler and lint icon names at build time. Nothing here is
// required to use the set: `<svg><use href="icons.svg#search"/></svg>` works
// with no JavaScript at all.
import names from './names.js';

/** Every symbol id in the sprite, sorted. */
export const ICON_NAMES = Object.freeze(names.slice());

/** The drawing grid. Icons are authored on 24 with a live area of 20×20. */
export const GRID = 24;

/**
 * The stroke the set was exported at, on a weight axis running 1.0–2.5.
 *
 * This is metadata, not a knob. The geometry is weight-aware — every clearance
 * in the set is a ratio of the stroke rather than a constant — so a different
 * weight is a different drawing, not the same paths at a different
 * `stroke-width`. Re-export the set for another weight; never restyle it
 * downstream, and in particular never set `stroke-width` across a whole icon:
 * the mask strokes are 5–6 units wide on purpose, and overriding them to the
 * nominal weight closes every moat in the set.
 */
export const STROKE = 2;

/**
 * The sprite's URL, resolved against this module so a bundler fingerprints and
 * emits it like any other asset. Falls back to a bare relative path in runtimes
 * without `import.meta.url`.
 */
export const spriteUrl = (() => {
  try {
    return new URL('../icons.svg', import.meta.url).href;
  } catch {
    return 'icons.svg';
  }
})();

const nameSet = new Set(names);

/** Whether the set contains `name`. */
export function hasIcon(name) {
  return nameSet.has(name);
}

/**
 * `href` for a `<use>` element, e.g. `useHref('search')`.
 * Pass `sprite` when the file is served from somewhere other than this package.
 */
export function useHref(name, sprite = spriteUrl) {
  return `${sprite}#${name}`;
}

export default ICON_NAMES;
