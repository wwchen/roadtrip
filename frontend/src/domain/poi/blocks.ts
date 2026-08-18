// The POI page's block vocabulary and its one fixed order.
//
// From the M0 screens doc, 4a ("One order, thirteen blocks"): *the sequence never
// changes. A type omits blocks; it does not reorder them.* That rule only means
// anything if the order lives in exactly one place, so it lives here — as data —
// and `PoiPageShell` renders whatever it is told in this order rather than in
// whatever order the JSX happens to be written.
//
// Everything above `RULE_BEFORE` answers "can I stay here, and when". Everything
// below answers "tell me more", and a camper who never scrolls past the rule has
// lost nothing.

export type PoiBlockId =
  | 'hero'
  | 'identity'
  | 'actions'
  | 'availability'
  | 'glance'
  | 'gettingThere'
  | 'goodToKnow'
  | 'specs'
  | 'contact'
  | 'links'
  | 'nearby'
  | 'verified'
  | 'provenance';

/**
 * The blocks, in order, grouped by hairline.
 *
 * A group is what a divider separates, not what a divider follows: dividers are
 * drawn *between adjacent groups that both have something in them*, so a type that
 * omits a whole group leaves no stray rule behind. That is the difference between
 * "the charger page has no availability" and "the charger page has an empty slot
 * where availability would be".
 */
export const POI_BLOCK_GROUPS: readonly (readonly PoiBlockId[])[] = [
  ['hero', 'identity', 'actions'],
  ['availability'],
  ['glance'],
  ['gettingThere', 'goodToKnow'],
  // ── the rule ──
  ['specs', 'contact', 'links'],
  ['nearby'],
] as const;

/**
 * The group index the rule is drawn before.
 *
 * Visually it is the same hairline as any other group boundary; it is named
 * because it is the fold the block order is designed around, and because a type
 * that omits every block above it (a dropped pin) should not draw it.
 */
export const RULE_BEFORE_GROUP = 4;

/** The blocks that live in the sunken footer bar rather than in the body flow. */
export const POI_FOOTER_BLOCKS: readonly PoiBlockId[] = ['verified', 'provenance'] as const;

/** Every body block, flattened — the canonical order, and the length 4a counts. */
export const POI_BLOCK_ORDER: readonly PoiBlockId[] = [
  ...POI_BLOCK_GROUPS.flat(),
  ...POI_FOOTER_BLOCKS,
] as const;
