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
  campgroundsRequested: true,
};

/** Below the campground zoom gate: nothing has been asked for yet, so campgrounds carry no count. */
const NOT_REQUESTED: ViewportPois = {
  buckets: EMPTY_BUCKETS,
  counts: { cg: 0, pf: 0, sc: 0 },
  campgroundsRequested: false,
};

/**
 * The panel positions itself `absolute` against the map shell (see `.rt-legend` in
 * `legend.css`), so the catalog gives it a positioned ancestor the way the real map
 * container does. `map: null` is enough: `LegendPanel` only touches the map to close
 * the mobile sheet on a map click, guarded by `if (!map) return`.
 */
function MapCorner({ hiddenOverlays = [], children }: { hiddenOverlays?: OverlayKey[]; children: ReactNode }) {
  useEffect(() => {
    useMapStore.setState({ hiddenOverlays });
    return () => useMapStore.setState({ hiddenOverlays: [] });
  }, [hiddenOverlays]);

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
          'The layers panel: what is on the map, how much of it is in view, and the ' +
          'basemap/satellite picker (`BasemapPicker`, rendered inside it). Narrowing ' +
          'campgrounds by agency lives in the topbar filter.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Campgrounds requested: every layer carries its count in view. */
export const CampgroundsInView: Story = {
  render: () => (
    <MapCorner>
      <LegendPanel pois={REQUESTED} />
    </MapCorner>
  ),
};

/** Below the campground zoom gate: campgrounds carry no count yet. */
export const CampgroundsNotYetRequested: Story = {
  render: () => (
    <MapCorner>
      <LegendPanel pois={NOT_REQUESTED} />
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
