import { Banner } from '@ui';
import { PlaceSurface } from '@/features/place/PlaceSurface';
import { poiFromUrl } from '@/lib/poi-url';
import './poi-page-shell.css';

/** One place, opened cold: the same `?poi=<id>` the map deep-links to. */
export function PoiPage() {
  // Read once, at module-render time: this page has no in-page navigation, so the
  // parameter cannot change without a reload.
  const poiId = poiFromUrl();

  return (
    <main className="rt-poi-page">
      {poiId == null ? (
        <Banner status="warning" title="No place requested">
          <p>
            This page opens one place at a time. Add <code>?poi=</code> and an id, or pick a pin on
            the map.
          </p>
        </Banner>
      ) : (
        <PlaceSurface poiId={poiId} arrival="cold" />
      )}
    </main>
  );
}
