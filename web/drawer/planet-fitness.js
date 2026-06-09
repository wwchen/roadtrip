// Planet Fitness drawer. OSM-imported gym pins; the primary CTA is Google
// Maps for routing, secondary is the planetfitness.com page when upstream
// has a website tag.

import { escapeHtml, callButtonsHTML } from '../core.js';
import { openDrawer } from './chrome.js';
import {
  buildSubline,
  distanceTo,
  drawerHeader,
  directionsButtonHTML,
  openHydratedDrawer,
  reviveJsonProp,
  upstreamHTML,
} from './shared.js';

export function openPlanetFitnessDrawer(f) {
  // Slim /api/pois ships only id + geometry + category. Hydrate first.
  openHydratedDrawer(f, openDrawer, renderPlanetFitnessDrawer);
}

function renderPlanetFitnessDrawer(f) {
  const p = f.properties;
  reviveJsonProp(p, 'upstream');
  const [lng, lat] = f.geometry.coordinates;
  // Reading order matches the SC drawer: street, then city, then state+zip
  // as a single token. Empty pieces drop out so a missing zip doesn't
  // leave a stray ", ".
  const addrLine = [p.street, p.city, [p.state, p.postcode].filter(Boolean).join(' ')]
    .filter(Boolean).join(', ');
  const sub = buildSubline([addrLine, distanceTo(lng, lat)]);
  // Primary CTA is Google Maps — same coords-query trick as superchargers,
  // works on iOS/Android/web and routes from the user's current location.
  const gmapsLabel = encodeURIComponent('Planet Fitness');
  const gmapsUrl = `https://www.google.com/maps/search/?api=1&query=${gmapsLabel}%20${lat},${lng}`;
  // Secondary: the OSM website tag if upstream had one; otherwise the
  // planetfitness.com search by city/state.
  const pfSearch =
    'https://www.planetfitness.com/gyms?q=' + encodeURIComponent([p.city, p.state].filter(Boolean).join(' '));
  const pfUrl = p.website || pfSearch;
  const callBtns = callButtonsHTML(p.phone);

  // Pills row: 24/7 / hours / wheelchair access. Each is dropped if the
  // upstream OSM tag is absent — sparse data should render sparse, not
  // with empty placeholder pills.
  const pills = [
    p.opening_hours ? `<span class="pill">${escapeHtml(p.opening_hours)}</span>` : '',
  ].filter(Boolean).join(' ');

  const dirBtn = directionsButtonHTML({ name: p.name || 'Planet Fitness', lng, lat, kind: 'PF' });
  openDrawer(`
    ${drawerHeader(p.name || 'Planet Fitness', sub)}
    <div class="cg-actions">
      ${dirBtn}
      <a class="cg-btn cg-btn-primary" href="${gmapsUrl}" target="_blank" rel="noopener">Open in Google Maps</a>
      <a class="cg-btn cg-btn-secondary" href="${pfUrl}" target="_blank" rel="noreferrer">Planet Fitness page</a>
      ${callBtns}
    </div>
    ${pills ? `<div class="pills">${pills}</div>` : ''}
    ${upstreamHTML(p.upstream)}
  `);
}
