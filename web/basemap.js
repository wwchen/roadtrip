import { state } from './core.js';

export const STATUS_COLOR = {
  OPEN: '#e82127',
  EXPANDING: '#e82127',
  CONSTRUCTION: '#f5a623',
  PERMIT: '#f7d56e',
  VOTING: '#f7d56e',
  PLAN: '#bfbfbf',
  CLOSED_TEMP: '#333',
  CLOSED_PERM: '#333',
};

export const FILTER_GROUP = {
  OPEN: 'open', EXPANDING: 'open',
  CONSTRUCTION: 'construction',
  PERMIT: 'permit', VOTING: 'permit',
  PLAN: 'plan',
  CLOSED_TEMP: 'closed', CLOSED_PERM: 'closed',
};

// Available basemaps. Value passed to map.setStyle() is either a style URL
// (vector) or a style object (raster). Free and key-less unless noted.
export const BASEMAPS = {
  'openfreemap-liberty': { name: 'OpenFreeMap Liberty',  style: 'https://tiles.openfreemap.org/styles/liberty' },
  'openfreemap-bright':  { name: 'OpenFreeMap Bright',   style: 'https://tiles.openfreemap.org/styles/bright' },
  'openfreemap-positron':{ name: 'OpenFreeMap Positron', style: 'https://tiles.openfreemap.org/styles/positron' },
  'carto-voyager':       { name: 'Carto Voyager',        style: rasterStyle('voyager') },
  'carto-positron':      { name: 'Carto Positron',       style: rasterStyle('light_all') },
  'carto-dark':          { name: 'Carto Dark Matter',    style: rasterStyle('dark_all') },
  'osm':                 { name: 'OpenStreetMap',        style: osmStyle() },
};
export const DEFAULT_BASEMAP = 'openfreemap-liberty';

function rasterStyle(cartoVariant) {
  return {
    version: 8,
    sources: {
      basemap: {
        type: 'raster',
        tiles: ['a','b','c','d'].map(s => `https://${s}.basemaps.cartocdn.com/rastertiles/${cartoVariant}/{z}/{x}/{y}@2x.png`),
        tileSize: 256,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>',
      },
    },
    layers: [{ id: 'basemap', type: 'raster', source: 'basemap' }],
  };
}
function osmStyle() {
  return {
    version: 8,
    sources: {
      basemap: {
        type: 'raster',
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        tileSize: 256,
        attribution: '&copy; OpenStreetMap contributors',
      },
    },
    layers: [{ id: 'basemap', type: 'raster', source: 'basemap' }],
  };
}

export function getInitialBasemapKey() {
  const saved = localStorage.getItem('basemap') || DEFAULT_BASEMAP;
  return BASEMAPS[saved] ? saved : DEFAULT_BASEMAP;
}

export function initBasemapPicker(initialKey) {
  const sel = document.getElementById('basemap-select');
  for (const [key, bm] of Object.entries(BASEMAPS)) {
    const opt = document.createElement('option');
    opt.value = key; opt.textContent = bm.name;
    sel.appendChild(opt);
  }
  sel.value = initialKey;
  sel.addEventListener('change', () => {
    localStorage.setItem('basemap', sel.value);
    // diff:false forces a full style reload, which is what we want — the
    // default (diff:true) does an incremental merge that keeps our overlay
    // sources/layers in place but does NOT fire style.load, so our reinstall
    // hook never runs. Full reload wipes everything cleanly and emits the
    // event we need.
    state.map.setStyle(BASEMAPS[sel.value].style, { diff: false });
  });
}

// Esri World Imagery as an optional underlay. Inserted just above the basemap's
// background so roads, parks, and labels still draw on top. Free for
// non-commercial use with attribution per Esri's ToS.
const SATELLITE_SOURCE = 'esri-imagery';
const SATELLITE_LAYER = 'esri-imagery-raster';
export function installSatellite() {
  const { map } = state;
  if (!document.getElementById('f-satellite').checked) return;
  if (map.getLayer(SATELLITE_LAYER)) return;
  map.addSource(SATELLITE_SOURCE, {
    type: 'raster',
    tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
    tileSize: 256,
    maxzoom: 19,
    attribution: 'Tiles &copy; Esri, Maxar, Earthstar Geographics',
  });
  const afterBg = map.getStyle().layers.find(l => l.type !== 'background');
  map.addLayer({ id: SATELLITE_LAYER, type: 'raster', source: SATELLITE_SOURCE }, afterBg?.id);
}

export function bindSatelliteToggle() {
  document.getElementById('f-satellite').addEventListener('change', () => {
    if (!state.mapReady) return;
    if (state.map.getLayer(SATELLITE_LAYER)) state.map.removeLayer(SATELLITE_LAYER);
    installSatellite();
  });
}
