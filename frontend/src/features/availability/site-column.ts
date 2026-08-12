// How wide the matrix's frozen "Site" column is.
//
// This is the matrix's only persisted UI state.
const STORAGE_KEY = 'cg.siteMatrix.siteColumnWidth';

export const DEFAULT_SITE_COLUMN_WIDTH = 128;
/**
 * The width this defaulted to before it was narrowed.
 *
 * Read as "never resized" rather than as a deliberate choice: everyone who had
 * used the grid before the change had the old default persisted, and honouring it
 * would have left them all on a column nobody picked. A user who genuinely wants
 * 178px is one drag away.
 */
const LEGACY_DEFAULT_SITE_COLUMN_WIDTH = 178;
const MIN_SITE_COLUMN_WIDTH = 88;
const MAX_SITE_COLUMN_WIDTH = 270;

export function clampSiteColumnWidth(width: number): number {
  return Math.max(MIN_SITE_COLUMN_WIDTH, Math.min(MAX_SITE_COLUMN_WIDTH, Math.round(width)));
}

/**
 * The remembered width, or the default.
 *
 * Reads defensively: Safari's private mode throws on `localStorage` access rather
 * than returning null, and a grid that will not render because of a storage
 * preference would be a poor trade.
 */
export function loadSiteColumnWidth(): number {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const width = parseInt(raw ?? '', 10);
    if (Number.isFinite(width)) {
      if (width === LEGACY_DEFAULT_SITE_COLUMN_WIDTH) return DEFAULT_SITE_COLUMN_WIDTH;
      return clampSiteColumnWidth(width);
    }
  } catch {
    // Non-fatal: default silently.
  }
  return DEFAULT_SITE_COLUMN_WIDTH;
}

/** Remember a width. Silent on failure — a blocked write must not break a drag. */
export function saveSiteColumnWidth(width: number): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, String(clampSiteColumnWidth(width)));
  } catch {
    // Private mode / quota. The column still resizes, it just will not persist.
  }
}
