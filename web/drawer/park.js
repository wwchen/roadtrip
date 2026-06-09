// National Park / State Park drawer. Single entry, kind-discriminator picks
// the labels and the external-link fallback.

import { escapeHtml } from '../core.js';
import { openDrawer } from './chrome.js';
import {
  buildSubline,
  distanceTo,
  drawerHeader,
  directionsButtonHTML,
  reviveJsonProp,
  upstreamHTML,
} from './shared.js';

// Build a reasonable external link for a park by name.
function externalParkLink(kind, name, stateName) {
  if (kind === 'np') {
    // NPS doesn't expose a deterministic slug, so a search URL is reliable.
    const q = encodeURIComponent(name);
    return [`https://www.nps.gov/findapark/advanced-search.htm?q=${q}`, 'nps.gov'];
  }
  // State Parks — no unified URL; do a Google search scoped to name + state
  const q = encodeURIComponent(`${name} ${stateName} state park`);
  return [`https://www.google.com/search?q=${q}`, 'search'];
}

export function openParkDrawer(kind, feature, lngLat) {
  const p = feature.properties;
  reviveJsonProp(p, 'upstream');
  const name = p.Unit_Nm || p.Loc_Nm || 'Park';
  const stateName = p.State_Nm || '';
  const acres = p.GIS_Acres ? Number(p.GIS_Acres).toLocaleString() + ' acres' : '';
  const mgr = p.Mang_Name || '';
  const [url, label] = externalParkLink(kind, name, stateName);
  const sub = buildSubline([
    kind === 'np' ? 'National Park' : 'State Park',
    stateName,
    distanceTo(lngLat.lng, lngLat.lat),
  ]);
  const pills = [
    mgr && mgr !== 'National Park Service' ? `<span class="pill">${escapeHtml(mgr)}</span>` : '',
    acres ? `<span class="pill">${escapeHtml(acres)}</span>` : '',
  ].filter(Boolean).join('');
  const primaryBtn = url
    ? `<a class="cg-btn cg-btn-primary" href="${url}" target="_blank" rel="noreferrer">${escapeHtml(label === 'nps.gov' ? 'Open on nps.gov' : 'Search ' + label)}</a>`
    : '';
  const dirBtn = directionsButtonHTML({ name, lng: lngLat.lng, lat: lngLat.lat, kind: kind === 'np' ? 'NP' : 'SP' });
  openDrawer(`
    ${drawerHeader(name, sub)}
    <div class="cg-actions">${dirBtn}${primaryBtn}</div>
    ${pills ? `<div class="pills">${pills}</div>` : ''}
    ${upstreamHTML(p.upstream)}
  `);
}
