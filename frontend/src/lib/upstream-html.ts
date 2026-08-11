// Sanitising provider HTML.
//
// Some providers ship HTML, not text: RIDB
// (recreation.gov, so NPS/USFS/BLM) puts markup in `FacilityUseFeeDescription`,
// `FacilityDirections` and campground descriptions. It is third-party content that
// reaches a page verbatim, so this is the one place in the React tree where
// `dangerouslySetInnerHTML` is justified — and the reason it is justified is this
// function.
//
// Whitelist, not blacklist, and parsed rather than regexed: `DOMParser` builds an
// inert document, the tree is walked, and anything not allowed is replaced by its own
// text content. A disallowed tag therefore loses its markup without losing its words.
//
// Two things it deliberately does NOT do: promote fields (a first-class UI value like
// a description or a photo belongs in the POI DTO, promoted by the ETL, not scraped
// out of a raw blob here), and trust `href`.

/** Structural tags plus safe inline. `<font>`/`<span style>` are unwrapped. */
const ALLOWED_TAGS = new Set([
  'H2',
  'H3',
  'H4',
  'P',
  'STRONG',
  'EM',
  'B',
  'I',
  'U',
  'UL',
  'OL',
  'LI',
  'BR',
  'A',
]);

const ALLOWED_ATTRS = new Map<string, Set<string>>([['A', new Set(['href', 'title'])]]);

/** Schemes a link may use. Everything else — `javascript:`, `data:`, `vbscript:` — is dropped. */
const SAFE_HREF = /^(https?:|mailto:|tel:|\/|#|$)/i;

/** Whether a string already carries markup, or is plain text to be paragraphed. */
const LOOKS_LIKE_HTML = /<\/?[a-z][\s\S]*>/i;

export function sanitizeUpstreamHtml(html: unknown): string {
  if (typeof html !== 'string' || !html.trim()) return '';
  // Wrapped in a div so the walk has a single root regardless of what arrives.
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const root = doc.body.firstElementChild;
  if (!root) return '';
  scrub(root);
  return root.innerHTML;
}

function scrub(node: Element): void {
  // A copy, because `scrub` mutates the live child list.
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) continue;
    if (child.nodeType !== Node.ELEMENT_NODE) {
      child.remove();
      continue;
    }

    const element = child as Element;
    if (!ALLOWED_TAGS.has(element.tagName)) {
      // Unwrap: sanitise the children, hoist them into this node's place, drop the
      // element. Keeps the text of a `<span style=…>` or `<font>` without its markup.
      scrub(element);
      while (element.firstChild) element.parentNode?.insertBefore(element.firstChild, element);
      element.remove();
      continue;
    }

    const allowed = ALLOWED_ATTRS.get(element.tagName) ?? new Set<string>();
    for (const attr of Array.from(element.attributes)) {
      if (!allowed.has(attr.name)) element.removeAttribute(attr.name);
    }

    if (element.tagName === 'A') {
      const href = element.getAttribute('href') ?? '';
      if (!SAFE_HREF.test(href)) {
        element.removeAttribute('href');
      } else if (href) {
        // Provider links leave the app, so they get the full opener guard.
        element.setAttribute('target', '_blank');
        element.setAttribute('rel', 'noopener noreferrer');
      }
    }

    scrub(element);
  }
}

/**
 * A description as sanitised HTML.
 *
 * Providers send either markup or plain text with blank-line paragraphs; the second
 * is escaped and paragraphed before sanitising, so a text description keeps its
 * shape instead of collapsing into one run.
 */
export function descriptionHtml(description: unknown): string {
  if (typeof description !== 'string' || !description.trim()) return '';
  const text = description.trim();
  if (LOOKS_LIKE_HTML.test(text)) return sanitizeUpstreamHtml(text);
  // Escaping happens by construction here: the text is set as a text node and read
  // back as markup, so there is no hand-rolled escaper to get wrong.
  const paragraphs = text.split(/\n{2,}/).map((part) => {
    const p = document.createElement('p');
    // A single newline inside a paragraph is a line break, as the original had it.
    part
      .trim()
      .split('\n')
      .forEach((line, index) => {
        if (index > 0) p.appendChild(document.createElement('br'));
        p.appendChild(document.createTextNode(line));
      });
    return p.outerHTML;
  });
  return sanitizeUpstreamHtml(paragraphs.join(''));
}

export interface UpstreamDecorations {
  /** RIDB's immediate parent rec area, when the record was fetched with `?full=true`. */
  parentName: string | null;
  /** Sanitised fee and cancellation markup. */
  feesHtml: string;
  stayLimit: string;
  /** Sanitised driving-directions markup. */
  directionsHtml: string;
}

/**
 * The auxiliary fields worth showing out of a verbatim upstream record.
 *
 * Returns data rather than the legacy version's ready-made HTML sections, so the
 * drawer decides the framing and the sanitiser's output is the only markup in play.
 */
export function upstreamDecorations(upstream: unknown): UpstreamDecorations {
  const empty: UpstreamDecorations = {
    parentName: null,
    feesHtml: '',
    stayLimit: '',
    directionsHtml: '',
  };
  if (!upstream || typeof upstream !== 'object') return empty;

  const record = upstream as Record<string, unknown>;
  return {
    parentName: parentRecAreaName(record.RECAREA),
    feesHtml: sanitizeUpstreamHtml(record.FacilityUseFeeDescription),
    stayLimit: typeof record.StayLimit === 'string' ? record.StayLimit.trim() : '',
    directionsHtml: sanitizeUpstreamHtml(record.FacilityDirections),
  };
}

/** The first RECAREA entry is the immediate parent (a "Buffalo National River"). */
function parentRecAreaName(recArea: unknown): string | null {
  if (!Array.isArray(recArea) || recArea.length === 0) return null;
  const name = (recArea[0] as { RecAreaName?: unknown } | undefined)?.RecAreaName;
  return typeof name === 'string' && name.trim() ? name.trim() : null;
}
