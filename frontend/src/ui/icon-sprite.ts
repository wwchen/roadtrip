// ---------------------------------------------------------------------------
// The Open Icons sprite, inlined into the document.
//
// LDS resolves every glyph against the sprite file inside `@lew-ds/open-icons`
// and emits `<svg class="lds-icon"><use href=".../icons.svg#name"></svg>`. That
// is correct for the 120 symbols drawn from paths alone, and silently mutilates
// the other 54.
//
// A symbol whose artwork carries a `<mask>` refers to it the only way SVG allows
// — `mask="url(#k-warning-fill)"`, an id lookup. The set leans on this hard: its
// README explains that the moats between strokes are CUT rather than drawn, which
// is also why `icons.css` forbids restyling `stroke-width`. So a mask that does
// not resolve is not a cosmetic loss; it is most of the drawing.
//
// Getting those masks to apply takes three things, and each one alone looks like
// it should be enough:
//
//   1. The sprite in this document. Through an external `<use>` the id lookup
//      runs against the host document rather than the file the symbol was cloned
//      from, resolves to nothing, and the masked group is dropped entirely —
//      `warning-fill` and `check-circle-fill` render as nothing at all, `people`
//      loses two of its three figures, `download` its tray.
//   2. `setIconSprite('')`, so components emit a same-document `href="#name"`.
//      Inlined ids do nothing while a glyph still points across files.
//   3. The sprite NOT hidden with `display:none` — see `showSprite` below. This
//      is the one that hides: with it, everything renders and the icons still
//      look subtly wrong, which is worse than blank.
// ---------------------------------------------------------------------------
import spriteMarkup from '@lew-ds/open-icons/icons.svg?raw';
import { setIconSprite } from '@lew-ds/lds-react';

/** Anchors the idempotence check, so a second call is a no-op not a second copy. */
const SPRITE_ELEMENT_ID = 'lds-icon-sprite';

/**
 * The empty sprite URL, which makes `resolveSprite` yield a bare `#name` — a
 * same-document reference. LDS documents `setIconSprite` as "point every
 * component at a different copy of the sprite"; pointing it at this document is
 * the same move with no copy to serve.
 */
const SAME_DOCUMENT_SPRITE = '';

/**
 * Hide the sprite by geometry rather than by `display`.
 *
 * `display: none` on the sprite — or on any ancestor of it — takes its `<mask>`
 * elements out of the render tree, and a mask that is not in the render tree
 * does not apply. The reference still resolves, so nothing errors and nothing
 * disappears: the masked group just draws unmasked. `check-circle-fill` becomes
 * a blank disc, `warning-fill` a blank triangle, `people` a crowd with no gaps
 * between the figures.
 *
 * A zero-sized, clipped, absolutely-positioned box takes the sprite out of
 * layout while leaving it rendered, which is what the masks need. Inline rather
 * than a class in `icons.css` on purpose: this element is created here and
 * nowhere else, and a stylesheet that failed to load would put a blank 300×150
 * replaced element at the top of every page.
 */
const OFFSCREEN = 'position:absolute;width:0;height:0;overflow:hidden';

/**
 * Inline the sprite and point LDS at it. Call once per document, before anything
 * renders — a glyph that mounts first keeps the href it was given.
 *
 * `innerHTML` is safe here and nowhere near user input: `spriteMarkup` is a
 * build-time asset from a pinned dependency, inlined by Vite's `?raw`, so the
 * string is fixed when the bundle is.
 */
export function installIconSprite(): void {
  setIconSprite(SAME_DOCUMENT_SPRITE);
  if (document.getElementById(SPRITE_ELEMENT_ID)) return;

  const holder = document.createElement('div');
  holder.id = SPRITE_ELEMENT_ID;
  holder.setAttribute('style', OFFSCREEN);
  holder.setAttribute('aria-hidden', 'true');
  holder.innerHTML = spriteMarkup;

  // The sprite ships as `<svg style="display:none">`, which is the right default
  // for a file referenced across documents and exactly wrong once it is inlined.
  // Overwritten rather than removed so any other inline style upstream adds
  // survives.
  holder.querySelector('svg')?.style.setProperty('display', 'block');

  document.body.prepend(holder);
}
