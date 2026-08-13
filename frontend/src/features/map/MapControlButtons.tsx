// Zoom and locate-me, replacing MapLibre's own bottom-right controls.
//
// The library's buttons are 29px, sized for a mouse pointer, and unstyled
// against the rest of this app's chrome. These call the same map methods
// (`zoomIn`/`zoomOut`/`GeolocateControl.trigger` via `useUserLocation`)
// through real buttons sized and themed like everything else here.
import { Icon } from '@lew-ds/lds-react';
import { useMapContext } from './MapProvider';
import './map-controls.css';

export interface MapControlButtonsProps {
  /** `useUserLocation()`'s `locate` — this button's only job is to call it. */
  onLocate: () => void;
}

export function MapControlButtons({ onLocate }: MapControlButtonsProps) {
  const { map } = useMapContext();

  return (
    <div className="rt-mapctl">
      <button
        type="button"
        className="rt-mapctl-locate"
        title="Use my location"
        aria-label="Use my location"
        onClick={onLocate}
      >
        <Icon name="location" size={18} aria-hidden="true" />
      </button>

      <div className="rt-mapctl-zoom" role="group" aria-label="Zoom">
        <button type="button" aria-label="Zoom in" onClick={() => map?.zoomIn()}>
          <Icon name="add" size={18} aria-hidden="true" />
        </button>
        <button type="button" aria-label="Zoom out" onClick={() => map?.zoomOut()}>
          <Icon name="minus" size={18} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
