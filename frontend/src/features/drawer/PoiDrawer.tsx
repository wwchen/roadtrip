import { useCallback, useEffect, type ReactNode } from 'react';
import { Banner, Button } from '@ui';
import type { FlatPoiFeature } from '@/lib/poi';
import { useMapStore } from '@/stores/mapStore';
import { Drawer } from './Drawer';
import { clearPoiFromUrl, showPoiInUrl } from '@/lib/poi-url';
import { poiPageFor } from '@/domain/poi/types/registry';
import { usePoiDetail } from '@/queries/poi-detail';

/**
 * The drawer for whatever pin is selected.
 *
 * The composition point: `mapStore.selectedPoiId` in, a hydrated category-specific
 * panel out. Everything upstream of it already existed — 4b's layer click handlers
 * record the selection and the empty-map click clears it — so this is the component
 * that was missing when a pin click appeared to do nothing.
 *
 * Replaces the imperative `openXDrawer(feature)` entry points: the drawer is a
 * function of the selection now, which is what makes a deep link work without a
 * separate code path (`?poi=<id>` seeds the same state a click would).
 */
export interface PoiDrawerProps {
  renderCampgroundAvailability?: (feature: FlatPoiFeature) => ReactNode;
}

export function PoiDrawer({ renderCampgroundAvailability }: PoiDrawerProps = {}) {
  const selectedPoiId = useMapStore((s) => s.selectedPoiId);
  const clearSelectedPoi = useMapStore((s) => s.clearSelectedPoi);
  const query = usePoiDetail(selectedPoiId);

  const close = useCallback(() => clearSelectedPoi(), [clearSelectedPoi]);

  // The visible URL follows the selection, so a drawer can be shared or reloaded.
  // Only `?poi=` moves; an active `?route=` survives untouched.
  useEffect(() => {
    if (selectedPoiId != null) showPoiInUrl(selectedPoiId);
    else clearPoiFromUrl();
  }, [selectedPoiId]);

  const open = selectedPoiId != null;
  const feature = query.data;
  const PoiPage = poiPageFor(feature?.properties);

  return (
    <Drawer open={open} onClose={close}>
      {query.isPending ? (
        // The vanilla equivalent was a "Loading…" header injected before the
        // fetch — same idea, same place in the layout, so the panel does not jump
        // when the content arrives.
        <div className="rt-drawer-loading">Loading…</div>
      ) : null}

      {query.isError ? (
        // The legacy path had no error branch at all: `openHydratedDrawer` had no
        // `.catch`, so a failed hydration left "Loading…" on screen indefinitely.
        <div className="rt-drawer-error">
          <Banner status="error" title="Could not load this place">
            <p>The details request failed. The pin is still on the map.</p>
            <Button variant="secondary" onClick={() => void query.refetch()}>
              Try again
            </Button>
          </Banner>
        </div>
      ) : null}

      {feature && PoiPage ? (
        // Every type takes the same props; a type that has no availability slot
        // simply never reads the node. That is what lets one call site serve seven
        // pages — see `domain/poi/types/registry.ts`.
        <PoiPage
          feature={feature}
          variant="panel"
          onClose={close}
          availability={renderCampgroundAvailability?.(feature)}
        />
      ) : null}

      {feature && !PoiPage ? (
        // A category with no renderer is a gap in the registry, not a state to
        // paper over — say so rather than showing an empty panel.
        <div className="rt-drawer-error">
          <Banner status="warning" title="No detail view for this place yet">
            <p>Category: {String(feature.properties?.category ?? 'unknown')}</p>
          </Banner>
        </div>
      ) : null}
    </Drawer>
  );
}
