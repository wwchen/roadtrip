// The POI page, at either width.
//
// Every pin opens the same page; the type decides which blocks appear. This
// component is where that promise is kept: it takes a bag of blocks keyed by id and
// renders them in `POI_BLOCK_GROUPS` order, so a type component cannot reorder
// anything even by accident — the order is not in its JSX.
//
// Two variants, one markup: `panel` is the map drawer (~520px, with a close
// button), `page` is the routed detail page (full width, with breadcrumbs). The
// difference is CSS and whether a couple of chrome blocks appear, not structure.
import type { ReactNode } from 'react';
import {
  POI_BLOCK_GROUPS,
  POI_FOOTER_BLOCKS,
  RULE_BEFORE_GROUP,
  type PoiBlockId,
} from './blocks';
import { PoiParentLink } from './PoiBlocks';
import type { PoiCrumb } from './model';
import './poi-page.css';

/**
 * The blocks a type supplies. A missing key is an omitted block, which is the
 * design's own rule: *a type omits blocks; it does not reorder them.*
 */
export type PoiBlockSlots = Partial<Record<PoiBlockId, ReactNode>>;

export type PoiPageVariant = 'panel' | 'page';

export interface PoiPageShellProps {
  variant?: PoiPageVariant;
  /** The one thing this place is inside. Routed page only — the drawer has no room. */
  parent?: PoiCrumb;
  blocks: PoiBlockSlots;
}

export function PoiPageShell({ variant = 'panel', parent, blocks }: PoiPageShellProps) {
  // A group renders only if something in it does, so a type that omits a whole
  // group leaves no empty band and no stray hairline behind it.
  const groups = POI_BLOCK_GROUPS.map((ids, index) => ({
    index,
    ids: ids.filter((id) => blocks[id] != null),
  })).filter((group) => group.ids.length > 0);

  const footer = POI_FOOTER_BLOCKS.filter((id) => blocks[id] != null);

  return (
    <article className={`rt-poi rt-poi--${variant}`}>
      {variant === 'page' && parent ? <PoiParentLink parent={parent} /> : null}

      {groups.map((group, position) => (
        <div className="rt-poi-group" key={group.index}>
          {/* Between groups only — the first rendered group never carries one, whichever
              group that turns out to be. The rule marking the "can I stay here" fold is
              the same hairline, named so it can be styled and tested apart; it is the
              one boundary that crosses `RULE_BEFORE_GROUP`, so a type with nothing
              above the fold does not draw it at all. */}
          {position > 0 ? (
            <div
              className={
                group.index >= RULE_BEFORE_GROUP && groups[position - 1].index < RULE_BEFORE_GROUP
                  ? 'rt-poi-rule rt-poi-rule--fold'
                  : 'rt-poi-rule'
              }
            />
          ) : null}
          {group.ids.map((id) => (
            <div className={`rt-poi-slot rt-poi-slot--${id}`} key={id}>
              {blocks[id]}
            </div>
          ))}
        </div>
      ))}

      {footer.length > 0 ? (
        <footer className="rt-poi-footer">
          {footer.map((id) => (
            <div className={`rt-poi-slot rt-poi-slot--${id}`} key={id}>
              {blocks[id]}
            </div>
          ))}
        </footer>
      ) : null}
    </article>
  );
}
