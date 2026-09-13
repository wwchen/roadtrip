import { useEffect, useState, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MapRuntimeContext, type MapContextValue } from '@/map/context';
import type { OverlayKey } from '@/map/overlays';
import { EMPTY_PIN_COLLECTION, type PinCollection } from '@/map/pins';
import { useMapStore } from '@/stores/mapStore';
import { LegendPanel } from './LegendPanel';
import type { ViewportPois } from './useViewportPois';
import './legend.css';

const EMPTY_BUCKETS: Record<OverlayKey, PinCollection> = {
  cg: EMPTY_PIN_COLLECTION,
  pf: EMPTY_PIN_COLLECTION,
  sc: EMPTY_PIN_COLLECTION,
};

const REQUESTED: ViewportPois = {
  buckets: EMPTY_BUCKETS,
  counts: { cg: 42, pf: 3, sc: 6 },
  agencies: new Map([
    ['USDA Forest Service', 21],
    ['California State Parks', 14],
    ['BC Parks', 7],
  ]),
  campgroundsRequested: true,
};

/** Below the campground zoom gate: nothing has been asked for yet, so no agency has a row. */
const NOT_REQUESTED: ViewportPois = {
  buckets: EMPTY_BUCKETS,
  counts: { cg: 0, pf: 0, sc: 0 },
  agencies: new Map(),
  campgroundsRequested: false,
};

/**
 * The panel positions itself `absolute` against the map shell (see `.rt-legend` in
 * `legend.css`), so the catalog gives it a positioned ancestor the way the real map
 * container does. `map: null` is enough: `LegendPanel` only touches the map to close
 * the mobile sheet on a map click, guarded by `if (!map) return`.
 */
function MapCorner({
  hiddenAgencies = [],
  hiddenOverlays = [],
  children,
}: {
  hiddenAgencies?: string[];
  hiddenOverlays?: OverlayKey[];
  children: ReactNode;
}) {
  useEffect(() => {
    useMapStore.setState({ hiddenAgencies, hiddenOverlays });
    return () => useMapStore.setState({ hiddenAgencies: [], hiddenOverlays: [] });
  }, [hiddenAgencies, hiddenOverlays]);

  return (
    <div style={{ position: 'relative', minHeight: 560 }}>
      <MapProviderStub>{children}</MapProviderStub>
    </div>
  );
}

/** A working basemap/satellite context, so `BasemapPicker`'s tiles and toggle respond. */
function MapProviderStub({ children }: { children: ReactNode }) {
  const [basemapKey, setBasemap] = useState('streets');
  const [satellite, setSatellite] = useState(false);

  const value: MapContextValue = {
    map: null,
    styleEpoch: 1,
    basemapKey,
    setBasemap,
    satellite,
    setSatellite,
  };

  return <MapRuntimeContext.Provider value={value}>{children}</MapRuntimeContext.Provider>;
}

const meta = {
  title: 'Map/LegendPanel',
  parameters: {
    docs: {
      description: {
        component:
          'The layers panel: what is on the map, how much of it is in view, the ' +
          'campground agency filter, and the basemap/satellite picker (`BasemapPicker`, ' +
          'rendered inside it).',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Campgrounds requested: three agencies in view, each with its own count. */
export const CampgroundsInView: Story = {
  render: () => (
    <MapCorner>
      <LegendPanel pois={REQUESTED} />
    </MapCorner>
  ),
};

/** Below the campground zoom gate: no agency has been seen yet. */
export const CampgroundsNotYetRequested: Story = {
  render: () => (
    <MapCorner>
      <LegendPanel pois={NOT_REQUESTED} />
    </MapCorner>
  ),
};

/** One agency switched off in the legend. */
export const AgencyHidden: Story = {
  render: () => (
    <MapCorner hiddenAgencies={['California State Parks']}>
      <LegendPanel pois={REQUESTED} />
    </MapCorner>
  ),
};

/** The campgrounds layer itself switched off. */
export const CampgroundsLayerOff: Story = {
  render: () => (
    <MapCorner hiddenOverlays={['cg']}>
      <LegendPanel pois={REQUESTED} />
    </MapCorner>
  ),
};
