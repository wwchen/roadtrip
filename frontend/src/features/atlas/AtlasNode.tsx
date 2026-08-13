// One row of the Atlas index, and — recursively — the subtree it discloses.
//
// Mirrors the disclosure idiom in `features/availability/SiteList.tsx`: a
// `<button aria-expanded>` with a ▾/▸ chevron whose children render only while
// open. Unlike that flat list, a row here expands into more rows of the same
// component, and each open row lazily fetches its own level (`useAtlasNode`
// enabled only once expanded), so the tree costs one request per opened node.
import { useState } from 'react';
import { Tag } from '@ui';
import type { AtlasNode as AtlasNodeData } from '@/api/atlas-api';
import { useAtlasNode } from '@/queries/atlas';

/**
 * The map page's POI deep link, as an href to another page.
 *
 * The map lives at `/` (index.html) and reads `?poi=<id>` on load — see
 * `lib/poi-url.ts`, which owns that contract for same-page updates. A campground
 * row links across pages, so it builds the href directly rather than mutating the
 * current URL.
 */
const MAP_POI_PARAM = 'poi';
const mapPoiHref = (poiId: number): string =>
  `/?${MAP_POI_PARAM}=${encodeURIComponent(String(poiId))}`;

export interface AtlasNodeProps {
  node: AtlasNodeData;
  /** Nesting level, from 0 at the root's children; drives the indent. */
  depth: number;
}

export function AtlasNode({ node, depth }: AtlasNodeProps) {
  const [expanded, setExpanded] = useState(false);
  const canExpand = node.has_children;

  // Only fetch this node's children once it is actually open.
  const level = useAtlasNode(node.key, { enabled: canExpand && expanded });

  const toggle = () => setExpanded((prev) => !prev);
  const indentStyle = { '--atlas-depth': depth } as React.CSSProperties;

  const label =
    node.kind === 'campground' && node.poi_id != null ? (
      <a className="atlas-label atlas-label-link" href={mapPoiHref(node.poi_id)}>
        {node.label}
      </a>
    ) : (
      <span className="atlas-label">{node.label}</span>
    );

  return (
    <div className="atlas-node" style={indentStyle}>
      <div className="atlas-row">
        {canExpand ? (
          <button
            type="button"
            className="atlas-disclosure"
            aria-expanded={expanded}
            aria-label={`${expanded ? 'Collapse' : 'Expand'} ${node.label}`}
            onClick={toggle}
          >
            <span className="atlas-chevron" aria-hidden="true">
              {expanded ? '▾' : '▸'}
            </span>
          </button>
        ) : (
          <span className="atlas-disclosure atlas-disclosure-leaf" aria-hidden="true" />
        )}

        {label}

        {node.child_count > 0 ? (
          <Tag size="sm" className="atlas-count">
            {node.child_count}
          </Tag>
        ) : null}

        {!expanded && node.teaser.length > 0 ? (
          <span className="atlas-teaser">{formatTeaser(node.teaser, node.child_count)}</span>
        ) : null}
      </div>

      {canExpand && expanded ? (
        <div className="atlas-children" role="group">
          {level.isPending ? (
            <div className="atlas-status" aria-busy="true">
              Loading…
            </div>
          ) : level.isError ? (
            <div className="atlas-status atlas-status-error">
              Couldn&rsquo;t load ·{' '}
              <button type="button" className="atlas-retry" onClick={() => level.refetch()}>
                Retry
              </button>
            </div>
          ) : level.data && level.data.children.length > 0 ? (
            level.data.children.map((child) => (
              <AtlasNode key={child.key} node={child} depth={depth + 1} />
            ))
          ) : (
            <div className="atlas-status">Nothing here.</div>
          )}
        </div>
      ) : null}
    </div>
  );
}

/** `· Zion · Bryce · Capitol Reef …`, with the ellipsis only when more remain. */
function formatTeaser(teaser: readonly string[], childCount: number): string {
  const shown = teaser.map((label) => `· ${label}`).join(' ');
  return childCount > teaser.length ? `${shown} …` : shown;
}
