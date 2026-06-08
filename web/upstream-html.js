// Decorate the campground drawer with rich fields from `properties.upstream`.
//
// The ETL stuffs the verbatim upstream record under properties.upstream
// (see Poi.Campground.extras). For RecGov / RIDB pins that means:
//   - MEDIA: array of {URL, Title, Description, IsPrimary, IsPreview, MediaType}
//   - FacilityDescription: HTML, h2-sectioned (Overview / Recreation / …)
//   - FacilityUseFeeDescription: HTML
//   - FacilityDirections, StayLimit: plain strings
//
// Other upstreams (BC Strapi, Aspira-WA join output) ship different shapes;
// the per-source helpers below return null when their field isn't present
// so the drawer can compose them additively.

import { escapeHtml } from './core.js';

// Whitelist HTML sanitizer. RIDB ships <h2>, <p>, <a>, <strong>, <em>, <ul>,
// <ol>, <li>, <br> and a sprinkling of <font>/<span> with style attrs we don't
// trust. Keep the structural tags + safe inline; drop everything else.
//
// Implementation: parse via DOMParser, walk, replace disallowed nodes with
// their text content. No regex; no innerHTML round-trip on user input.
const ALLOWED_TAGS = new Set([
  'H2', 'H3', 'H4', 'P', 'STRONG', 'EM', 'B', 'I', 'U',
  'UL', 'OL', 'LI', 'BR', 'A',
]);
const ALLOWED_ATTRS = {
  A: new Set(['href', 'title']),
};

export function sanitizeUpstreamHtml(html) {
  if (typeof html !== 'string' || !html.trim()) return '';
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const root = doc.body.firstElementChild;
  if (!root) return '';
  scrub(root);
  return root.innerHTML;
}

function scrub(node) {
  // Walk a copy of childNodes — scrub mutates the live list.
  const kids = Array.from(node.childNodes);
  for (const kid of kids) {
    if (kid.nodeType === Node.TEXT_NODE) continue;
    if (kid.nodeType !== Node.ELEMENT_NODE) {
      kid.remove();
      continue;
    }
    const tag = kid.tagName;
    if (!ALLOWED_TAGS.has(tag)) {
      // Replace the node with its (sanitized) children. Drops <span style="…">,
      // <font>, <script>, etc. without losing the inner text.
      scrub(kid);
      while (kid.firstChild) kid.parentNode.insertBefore(kid.firstChild, kid);
      kid.remove();
      continue;
    }
    // Strip non-whitelisted attrs.
    const allowed = ALLOWED_ATTRS[tag] || new Set();
    for (const attr of Array.from(kid.attributes)) {
      if (!allowed.has(attr.name)) kid.removeAttribute(attr.name);
    }
    if (tag === 'A') {
      const href = kid.getAttribute('href') || '';
      // Drop javascript:/data:/vbscript: hrefs. Allow http(s), mailto, tel,
      // and relative paths.
      if (!/^(https?:|mailto:|tel:|\/|#|$)/i.test(href)) {
        kid.removeAttribute('href');
      } else if (href) {
        kid.setAttribute('target', '_blank');
        kid.setAttribute('rel', 'noopener noreferrer');
      }
    }
    scrub(kid);
  }
}

/**
 * Pick the best hero image URL from a RIDB-style MEDIA array.
 * Preference: IsPrimary → IsPreview → first Image entry → null.
 */
export function pickHeroFromMedia(media) {
  if (!Array.isArray(media)) return null;
  const images = media.filter((m) => m && (m.MediaType === 'Image' || !m.MediaType) && m.URL);
  if (!images.length) return null;
  return (
    images.find((m) => m.IsPrimary)?.URL ||
    images.find((m) => m.IsPreview)?.URL ||
    images[0].URL
  );
}

/**
 * Section blocks (HTML strings) decorated from upstream. Returns an object
 * with named pieces so the drawer composer can place them where it wants;
 * each piece is empty string when the corresponding field isn't shipped.
 */
export function upstreamDecorations(upstream) {
  if (!upstream || typeof upstream !== 'object') {
    return { heroUrl: null, parentName: null, about: '', fees: '', meta: '' };
  }
  // RIDB shape (NPS / USFS / BLM via recreation.gov).
  const heroUrl = pickHeroFromMedia(upstream.MEDIA);
  // Parent park: RIDB returns RECAREA[] when fetched with ?full=true.
  // The first entry is the immediate parent (e.g. "Buffalo National
  // River" for a Steel Creek facility).
  const parentName = pickParentName(upstream.RECAREA);
  const description = sanitizeUpstreamHtml(upstream.FacilityDescription);
  const fees = sanitizeUpstreamHtml(upstream.FacilityUseFeeDescription);
  const directions = sanitizeUpstreamHtml(upstream.FacilityDirections);

  const about = description
    ? `<section class="cg-about"><h3>About</h3><div class="cg-html">${description}</div></section>`
    : '';
  const feesSection = fees
    ? `<section class="cg-fees"><h3>Fees & cancellation</h3><div class="cg-html">${fees}</div></section>`
    : '';
  // Stay limit + directions sit together — short, secondary info.
  const metaItems = [];
  const stayLimit = upstream.StayLimit?.trim();
  if (stayLimit) {
    metaItems.push(`<div><strong>Stay limit:</strong> ${escapeHtml(stayLimit)}</div>`);
  }
  if (directions) {
    metaItems.push(`<div><strong>Directions:</strong> <span class="cg-html">${directions}</span></div>`);
  }
  const meta = metaItems.length
    ? `<section class="cg-upstream-meta">${metaItems.join('')}</section>`
    : '';

  return { heroUrl, parentName, about, fees: feesSection, meta };
}

function pickParentName(recArea) {
  if (!Array.isArray(recArea) || !recArea.length) return null;
  const name = recArea[0]?.RecAreaName;
  return typeof name === 'string' && name.trim() ? name.trim() : null;
}
