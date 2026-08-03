// Page controller for `/`.
//
// `/` has two states: the landing, which asks for a route, and the map, which
// answers it. This decides which one you get and owns the transition between
// them. It is the only module that boots the map app, so "has the map been
// loaded yet" has exactly one answer.
//
// Why a state rather than a separate route: every existing link, bookmark and
// share URL still means the same thing, and a shared route deep-links straight
// past the landing to the map it was pointing at.

import { mountLanding } from './landing/landing.js';
import { loadMapStack } from './map-stack.js';

// Params that mean "you already asked for something specific" — a shared route
// or a shared POI. Landing would be in the way, so it is skipped. `map` is the
// explicit opt-out for anyone who wants the bare map (and for the smoke suite).
const DIRECT_TO_MAP_PARAMS = ['route', 'poi', 'map'];

// Above this width the panel is a rail with room for the map beside it, so the
// map is worth loading up front: it shows the ground the product covers while
// the user types. Below it the panel is the whole screen, the map would be
// behind an opaque surface, and loading it would be pure cost.
const MAP_ALONGSIDE_QUERY = '(min-width: 900px)';

const LANDING_ACTIVE_CLASS = 'rt-landing-active';

let landing = null;
let appBoot = null;

function wantsMapDirectly() {
  const params = new URLSearchParams(window.location.search);
  return DIRECT_TO_MAP_PARAMS.some(p => params.has(p));
}

/**
 * Load the map stack, then the map app. Idempotent — the landing calls this
 * eagerly on desktop and again on submit, and both share one boot.
 */
function bootMapApp() {
  if (!appBoot) {
    appBoot = loadMapStack().then(() => import('./app.js'));
  }
  return appBoot;
}

function dismissLanding() {
  document.body.classList.remove(LANDING_ACTIVE_CLASS);
  const host = document.getElementById('landing');
  if (host) host.hidden = true;
  landing?.dispose();
  landing = null;
}

async function planRoute(stops) {
  await bootMapApp();
  // Imported after app.js so the topbar is mounted; the module is already in
  // the graph by now, so this resolves from cache rather than re-fetching.
  const { startTrip } = await import('./topbar.js');
  // Leave the landing up if the hand-off could not happen — dropping the user
  // onto an empty map with no explanation is worse than staying put.
  if (startTrip(stops)) dismissLanding();
}

async function browseMap() {
  await bootMapApp();
  dismissLanding();
}

function init() {
  const host = document.getElementById('landing');
  if (!host) return;

  if (wantsMapDirectly()) {
    host.hidden = true;
    bootMapApp();
    return;
  }

  document.body.classList.add(LANDING_ACTIVE_CLASS);
  host.hidden = false;
  landing = mountLanding(host, { onPlan: planRoute, onBrowse: browseMap });

  if (window.matchMedia?.(MAP_ALONGSIDE_QUERY).matches) bootMapApp();
}

init();
