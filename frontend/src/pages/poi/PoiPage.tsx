import { Banner, Button, Skeleton } from '@ui';
import { poiPageFor } from '@/domain/poi/types/registry';
import { AvailabilityWeek } from '@/features/availability/AvailabilityWeek';
import { poiFromUrl } from '@/lib/poi-url';
import { usePoiDetail } from '@/queries/poi-detail';
import './poi-page-shell.css';

/**
 * A POI at page width.
 *
 * The same `?poi=<id>` the drawer deep-links to, opened as its own page instead of
 * over the map — which is what the block order was designed for: a park's list of
 * campgrounds and a state's list of parks are tables, and a 520px panel tiled beside
 * a map is not where a table wants to live.
 *
 * It shares every component with the drawer and adds nothing. If this page shows a
 * block the drawer does not, that is a bug in one of them, not a feature of this
 * one — the only differences are the breadcrumb trail and the width.
 */
export function PoiPage() {
  // Read once, at module-render time: this page has no in-page navigation, so the
  // parameter cannot change without a reload.
  const poiId = poiFromUrl();
  const query = usePoiDetail(poiId);
  const feature = query.data;
  const Poi = poiPageFor(feature?.properties);

  return (
    <main className="rt-poi-page">
      {poiId == null ? (
        <Banner status="warning" title="No place requested">
          <p>
            This page opens one place at a time. Add <code>?poi=</code> and an id, or pick a pin on
            the map.
          </p>
        </Banner>
      ) : null}

      {query.isPending && poiId != null ? <Skeleton height="320px" /> : null}

      {query.isError ? (
        <Banner status="error" title="Could not load this place">
          <p>The details request failed.</p>
          <Button variant="secondary" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </Banner>
      ) : null}

      {feature && Poi ? (
        <Poi
          feature={feature}
          variant="page"
          availability={<AvailabilityWeek feature={feature} />}
        />
      ) : null}

      {feature && !Poi ? (
        // A category with no renderer is a gap in the registry, not a state to paper
        // over — the same call the drawer makes, for the same reason.
        <Banner status="warning" title="No detail view for this place yet">
          <p>Category: {String(feature.properties?.category ?? 'unknown')}</p>
        </Banner>
      ) : null}
    </main>
  );
}
