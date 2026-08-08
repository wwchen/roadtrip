import { spriteUrl } from '@lew/open-icons';

// Where components look for the icon sprite.
//
// The prototypes hardcoded a relative `icons.svg`, which only resolves when the
// page sits next to the file. An installed package cannot assume that, so the
// default is the sprite's resolved URL inside @lew/open-icons and an app can
// repoint it once if it serves the file from somewhere else.
//
// Read through a getter rather than captured as a default parameter: a default
// is evaluated when the module is loaded, so a component would freeze whatever
// the value was at import time and ignore a later `setIconSprite`.
let sprite = spriteUrl;

/**
 * Point every component at a different copy of the sprite — e.g. one served
 * from your own CDN. Call once at startup, before anything renders.
 */
export function setIconSprite(url) {
  sprite = url;
}

/** The sprite URL components currently resolve against. */
export function getIconSprite() {
  return sprite;
}

/** Resolves a component's `iconHref` prop against the configured default. */
export function resolveSprite(iconHref) {
  return iconHref === undefined || iconHref === null ? sprite : iconHref;
}
