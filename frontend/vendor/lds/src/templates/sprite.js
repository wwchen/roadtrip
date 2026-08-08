import { resolveSprite } from '../icon-sprite.js';
import { escapeHtml } from './escape.js';

// One sprite reference, drawn the same way everywhere.
//
// Every component that shows a glyph emits exactly this element — same class,
// same aria-hidden, same `<use href="sprite#name">` — so an icon inside a button
// and an icon inside a banner are the same node, not two that happen to look
// alike. `iconHref` overrides the resolved sprite for a single call; leaving it
// undefined uses whatever `setIconSprite` last set.
export function spriteSvg(name, iconHref) {
  const href = resolveSprite(iconHref);
  return `<svg class="lds-icon" aria-hidden="true"><use href="${escapeHtml(`${href}#${name}`)}"></use></svg>`;
}
