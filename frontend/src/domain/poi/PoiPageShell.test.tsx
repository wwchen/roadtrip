// The one invariant the whole POI page rests on: the sequence never changes.
//
// A type omits blocks; it does not reorder them. These tests are what stops a type
// component from quietly getting that wrong, because nothing else would notice — a
// reordered page still renders.
import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { POI_BLOCK_ORDER, type PoiBlockId } from './blocks';
import { PoiPageShell, type PoiBlockSlots } from './PoiPageShell';

/** Every block, labelled with its own id, so DOM order is readable as block order. */
const everyBlock: PoiBlockSlots = Object.fromEntries(
  POI_BLOCK_ORDER.map((id) => [id, <span data-block={id}>{id}</span>]),
);

const renderedOrder = (): string[] =>
  [...document.querySelectorAll('[data-block]')].map((el) => el.getAttribute('data-block') ?? '');

const rules = () => document.querySelectorAll('.rt-poi-rule');
const folds = () => document.querySelectorAll('.rt-poi-rule--fold');

describe('the POI page shell', () => {
  test('renders every block in the canonical order', () => {
    render(<PoiPageShell blocks={everyBlock} />);

    expect(renderedOrder()).toEqual([...POI_BLOCK_ORDER]);
  });

  test('ignores the order the type happened to write its blocks in', () => {
    // Same blocks, reversed on the way in. The page comes out identical, which is
    // the property: a type component cannot express an order at all.
    const reversed = Object.fromEntries(
      [...POI_BLOCK_ORDER].reverse().map((id) => [id, <span data-block={id}>{id}</span>]),
    );

    render(<PoiPageShell blocks={reversed} />);

    expect(renderedOrder()).toEqual([...POI_BLOCK_ORDER]);
  });

  test('omits a block by leaving its key out, with nothing left behind', () => {
    render(<PoiPageShell blocks={{ identity: <span data-block="identity">Camp</span> }} />);

    expect(renderedOrder()).toEqual(['identity']);
    // One group, so no boundaries — a page with a single block draws no hairline.
    expect(rules()).toHaveLength(0);
    expect(document.querySelector('.rt-poi-footer')).toBeNull();
  });

  test('draws a hairline only between groups that both have content', () => {
    // Identity and specs sit either side of the fold with three empty groups
    // between them, so exactly one rule separates them — not four.
    render(
      <PoiPageShell
        blocks={{
          identity: <span data-block="identity">Camp</span>,
          specs: <span data-block="specs">Stay details</span>,
        }}
      />,
    );

    expect(rules()).toHaveLength(1);
    expect(folds()).toHaveLength(1);
  });

  test('marks the fold once, at the crossing, however many groups follow it', () => {
    render(<PoiPageShell blocks={everyBlock} />);

    expect(folds()).toHaveLength(1);
  });

  test('a type with nothing above the fold draws no fold', () => {
    render(<PoiPageShell blocks={{ specs: <span data-block="specs">What we know</span> }} />);

    expect(folds()).toHaveLength(0);
  });

  test('the freshness stamp and provenance sit in the footer, not the flow', () => {
    render(
      <PoiPageShell
        blocks={{
          identity: <span data-block="identity">Camp</span>,
          verified: <span data-block="verified">Verified 23 May</span>,
        }}
      />,
    );

    const footer = document.querySelector('.rt-poi-footer');
    expect(footer).not.toBeNull();
    expect(footer!.querySelector('[data-block="verified"]')).not.toBeNull();
  });

  test('the breadcrumb trail is the routed page’s, not the panel’s', () => {
    const crumbs = [{ label: 'Oregon' }, { label: 'Jasper SRS' }];
    const blocks: PoiBlockSlots = { identity: <span data-block="identity">Jasper</span> };

    const { rerender } = render(<PoiPageShell variant="panel" crumbs={crumbs} blocks={blocks} />);
    expect(screen.queryByLabelText('Breadcrumb')).toBeNull();

    rerender(<PoiPageShell variant="page" crumbs={crumbs} blocks={blocks} />);
    expect(screen.getByLabelText('Breadcrumb')).toBeInTheDocument();
  });
});

/** Guards the count 4a names, so "thirteen blocks" and the code cannot drift. */
test('there are thirteen blocks', () => {
  const ids: PoiBlockId[] = [...POI_BLOCK_ORDER];
  expect(ids).toHaveLength(13);
  expect(new Set(ids).size).toBe(13);
});
