import { Button } from '@ui';

import {
  CallButtons,
  DirectionsButton,
  DrawerHeader,
  Pills,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from './parts';
import type { DrawerContentProps } from './registry';

/**
 * Planet Fitness drawer. Port of web/drawer/planet-fitness.js.
 *
 * OSM-imported gym pins, so the data is sparse and the CTAs lean on other sites:
 * Google Maps for routing (the coords-query form works on iOS, Android and web and
 * routes from the user's own location), and the planetfitness.com page when the OSM
 * `website` tag exists, falling back to their gym search by city.
 */
export function PlanetFitnessDrawer({ feature, onClose }: DrawerContentProps) {
  const p = feature.properties;
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);
  const name = text(p.name) || 'Planet Fitness';

  // Same reading order as the supercharger drawer: street, city, then state and zip
  // as one token. Empty pieces drop out, so a missing zip leaves no stray comma.
  const address = [text(p.street), text(p.city), [text(p.state), text(p.postcode)].filter(Boolean).join(' ')]
    .filter(Boolean)
    .join(', ');

  const mapsUrl =
    lng != null && lat != null
      ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent('Planet Fitness')}%20${lat},${lng}`
      : '';
  const gymSearch = `https://www.planetfitness.com/gyms?q=${encodeURIComponent(
    [text(p.city), text(p.state)].filter(Boolean).join(' '),
  )}`;

  return (
    <>
      <DrawerHeader name={name} sub={subline([address, distance])} />
      <div className="rt-drawer-section rt-drawer-actions">
        <DirectionsButton name={name} lng={lng} lat={lat} kind="PF" onAdded={onClose} />
        {mapsUrl ? (
          <Button variant="primary" href={mapsUrl} target="_blank" rel="noopener">
            Open in Google Maps
          </Button>
        ) : null}
        <Button variant="secondary" href={text(p.website) || gymSearch} target="_blank" rel="noreferrer">
          Planet Fitness page
        </Button>
        <CallButtons phone={p.phone} />
      </div>
      {/* Sparse upstream data should render sparse rather than as empty placeholders. */}
      <Pills items={[text(p.opening_hours)]} />
      <UpstreamTable upstream={p.upstream} />
    </>
  );
}
