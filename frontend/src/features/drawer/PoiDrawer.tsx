import { useCallback, useEffect, type ReactNode } from 'react';
import { Banner, Button, EmptyState } from '@ui';
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

  // A failure with data behind it is a failed *refetch*, not a failed load:
  // react-query keeps the last good value and only flips `status` to error, so
  // `isError` and `data` are true together for the whole time the stale copy is
  // on screen. Branching on `isError` alone therefore drew the "didn't load"
  // card on top of a fully rendered, still-correct page — the two blocks below
  // are siblings, not a switch, so both painted.
  //
  // The distinction the user cares about is whether there is anything to read:
  // nothing to show is a dead end and takes over the panel; a stale copy is
  // still useful and keeps the page, saying only that the refresh failed.
  const loadFailed = query.isError && !feature;
  const refreshFailed = query.isError && !!feature;

  return (
    <Drawer open={open} onClose={close}>
      {query.isPending ? (
        // The vanilla equivalent was a "Loading…" header injected before the
        // fetch — same idea, same place in the layout, so the panel does not jump
        // when the content arrives.
        <div className="rt-drawer-loading">Loading…</div>
      ) : null}

      {loadFailed ? (
        // The legacy path had no error branch at all: `openHydratedDrawer` had no
        // `.catch`, so a failed hydration left "Loading…" on screen indefinitely.
        <div className="rt-drawer-error">
          <EmptyState
            icon="close-circle-fill"
            title="This place didn't load"
            body="We couldn't fetch its details. The map and everything else on it still work."
            actions={
              <>
                <Button variant="secondary" size="sm" iconStart="refresh" onClick={() => void query.refetch()}>
                  Try again
                </Button>
                <Button variant="tertiary" size="sm" iconStart="chevron-left" onClick={close}>
                  Back to the map
                </Button>
              </>
            }
          />
        </div>
      ) : null}

      {refreshFailed ? (
        // Deliberately a banner and not the card above: the page below it is
        // real, so taking the panel over would hide working content to report a
        // background failure.
        <div className="rt-drawer-error">
          <Banner
            status="warning"
            title="Couldn't refresh this place"
            actions={
              <Button variant="secondary" size="sm" iconStart="refresh" onClick={() => void query.refetch()}>
                Try again
              </Button>
            }
          >
            <p>These are the details we loaded earlier. They may be out of date.</p>
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
