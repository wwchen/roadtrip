// The blocks a POI page is assembled from.
//
// Each one renders a shape from `model.ts` and nothing else — no provider names, no
// category branches. `PoiPageShell` decides which appear and in what order (see
// `blocks.ts`); a type component decides what goes in them.
import type { ReactNode } from 'react';
import type { PoiCrumb, PoiLink, PoiNeighbour, PoiSpecList, PoiVerified } from './model';

/** Every block below the header uses the same small caps label. */
export function PoiBlockHeading({ children }: { children: ReactNode }) {
  return <h3 className="rt-poi-heading">{children}</h3>;
}

/**
 * One step up: the thing this place is inside.
 *
 * Not a chain. A full ancestry — state / park / campground — spends three lines
 * restating what the title and the subtitle already say, and the deepest step is
 * always the page you are looking at. The parent is the only step that tells you
 * something you cannot already see, so it is the only one printed.
 *
 * Renders as text until that parent has a page; `PoiPageShell` only shows it on the
 * routed page, since the drawer has no room and the map is already the context.
 */
export function PoiParentLink({ parent }: { parent: PoiCrumb }) {
  return (
    <nav className="rt-poi-crumbs" aria-label="Breadcrumb">
      {parent.href ? <a href={parent.href}>{parent.label}</a> : <span>{parent.label}</span>}
    </nav>
  );
}

export interface PoiHeroProps {
  url: string;
  alt: string;
}

/**
 * The photo, flush to the page edges.
 *
 * A background image rather than an `<img>` because the crop is the point — the
 * slot is a fixed band and the photo fills it — and the accessible name goes on the
 * `role="img"` wrapper instead.
 */
export function PoiHero({ url, alt }: PoiHeroProps) {
  return (
    <div
      className="rt-poi-hero"
      role="img"
      aria-label={alt}
      style={{ backgroundImage: `url('${encodeURI(url)}')` }}
    />
  );
}

export interface PoiIdentityProps {
  /** "Campground · Oregon State Parks" — what this is, and who runs it. */
  eyebrow?: ReactNode;
  title: string;
  /** The street address, or whatever locates the place. */
  subtitle?: ReactNode;
  /** The season verdict, when the type has one. */
  verdict?: ReactNode;
}

/**
 * No dismiss control lives here. The drawer shell owns one already — it has to,
 * because it is also what closes the loading and error states, which have no
 * identity block to hang a button off — and the routed page closes with browser
 * back.
 */
export function PoiIdentity({ eyebrow, title, subtitle, verdict }: PoiIdentityProps) {
  return (
    <header className="rt-poi-identity">
      <div className="rt-poi-identity-text">
        {eyebrow ? <p className="rt-poi-eyebrow">{eyebrow}</p> : null}
        <h2 className="rt-poi-title">{title}</h2>
        {subtitle ? <p className="rt-poi-subtitle">{subtitle}</p> : null}
        {verdict ? <div className="rt-poi-verdict">{verdict}</div> : null}
      </div>
    </header>
  );
}

/** The action row. Buttons come from the type component, which knows what they do. */
export function PoiActions({ children }: { children: ReactNode }) {
  return <div className="rt-poi-actions">{children}</div>;
}

/** A tag in the at-a-glance row. `absent` is the only state that takes a hue. */
export interface PoiTag {
  label: string;
  absent?: boolean;
}

export interface PoiGlanceProps {
  heading?: string;
  tags: PoiTag[];
  /** Carrier bars and the like — neutral by design: weak signal is information. */
  extra?: ReactNode;
}

export function PoiGlance({ heading = 'At a glance', tags, extra }: PoiGlanceProps) {
  return (
    <section className="rt-poi-block">
      <PoiBlockHeading>{heading}</PoiBlockHeading>
      {tags.length > 0 ? (
        <ul className="rt-poi-tags">
          {tags.map((tag) => (
            <li
              key={tag.label}
              className={tag.absent ? 'rt-poi-tag rt-poi-tag--absent' : 'rt-poi-tag'}
            >
              {tag.label}
            </li>
          ))}
        </ul>
      ) : null}
      {extra}
    </section>
  );
}

/** "Getting there" and "Good to know" — a heading and a paragraph of prose. */
export function PoiProse({ heading, children }: { heading: string; children: ReactNode }) {
  return (
    <section className="rt-poi-block">
      <PoiBlockHeading>{heading}</PoiBlockHeading>
      <div className="rt-poi-prose">{children}</div>
    </section>
  );
}

/**
 * The type's one spec list.
 *
 * Same component whether the heading says "Stay details", "Charging" or "The hike";
 * what changes is which type built it, which is the whole idea behind one block
 * order with per-type content.
 */
export function PoiSpecs({ list }: { list: PoiSpecList }) {
  return (
    <section className="rt-poi-block">
      <PoiBlockHeading>{list.heading}</PoiBlockHeading>
      <dl className="rt-poi-specs">
        {list.rows.map((row) => (
          <div className="rt-poi-spec" key={row.label}>
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export interface PoiContactProps {
  heading?: string;
  rows: { label: string; value: ReactNode }[];
}

export function PoiContact({ heading = 'Contact', rows }: PoiContactProps) {
  return (
    <section className="rt-poi-block">
      <PoiBlockHeading>{heading}</PoiBlockHeading>
      <dl className="rt-poi-specs">
        {rows.map((row) => (
          <div className="rt-poi-spec" key={row.label}>
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function PoiLinks({ links }: { links: PoiLink[] }) {
  return (
    <section className="rt-poi-block">
      <PoiBlockHeading>Links</PoiBlockHeading>
      <ul className="rt-poi-links">
        {links.map((link) => (
          <li key={link.href}>
            <a href={link.href} target="_blank" rel="noreferrer">
              {link.label}
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
}

export interface PoiNearbyProps {
  heading: string;
  items: PoiNeighbour[];
}

/**
 * The neighbours carousel — a horizontal scroller, not a grid.
 *
 * A grid would set the count; a scroller lets "10 closest" be however many there
 * are, and keeps the block one row tall on a phone.
 *
 * The scroller is focusable and labelled because it is the one block whose content
 * can sit off-screen: a mouse wheel and a finger reach the overflow, and without a
 * tab stop a keyboard never does. `items` is assumed non-empty — the type component
 * decides whether there is anything to show, so this never renders "0 closest".
 */
export function PoiNearby({ heading, items }: PoiNearbyProps) {
  return (
    <section className="rt-poi-block">
      <div className="rt-poi-block-head">
        <PoiBlockHeading>{heading}</PoiBlockHeading>
        <span className="rt-poi-block-meta">{items.length} closest · scroll</span>
      </div>
      <ul className="rt-poi-carousel" tabIndex={0} aria-label={heading}>
        {items.map((item) => (
          <li className="rt-poi-card" key={item.id}>
            {item.href ? (
              <a href={item.href}>{item.name}</a>
            ) : (
              <span className="rt-poi-card-name">{item.name}</span>
            )}
            <span className="rt-poi-card-meta">{item.meta}</span>
            {item.status ? <span className="rt-poi-card-status">{item.status}</span> : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * How fresh the record is.
 *
 * Lives in the footer bar with provenance because the two answer the same
 * question — how much to trust this page — rather than telling you anything about
 * the place itself.
 */
export function PoiVerifiedStamp({ verified }: { verified: PoiVerified }) {
  const className = verified.stale ? 'rt-poi-verified rt-poi-verified--stale' : 'rt-poi-verified';
  return (
    <span className={className}>
      Verified {verified.date}
      {verified.stale ? ' · check before booking' : ''}
    </span>
  );
}

/**
 * What is left of a provider record once everything that is trip content has been
 * promoted into a block above — collapsed, because provenance is a "what's
 * available" surface and not a primary read.
 */
export function PoiProvenance({ children }: { children: ReactNode }) {
  return (
    <details className="rt-poi-provenance">
      <summary>Where this comes from</summary>
      {children}
    </details>
  );
}
