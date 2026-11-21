import type { Meta, StoryObj } from '@storybook/react-vite';
import { MapRuntimeContext, type MapContextValue } from '@/map/context';
import { MapControlButtons } from './MapControlButtons';

const FRAME_WIDTH_PX = 360;
const FRAME_HEIGHT_PX = 240;
const MOBILE_VIEWPORT = 'mobile1';

const NO_MAP: MapContextValue = {
  map: null,
  styleEpoch: 1,
  basemapKey: 'streets',
  setBasemap: () => {},
  satellite: false,
  setSatellite: () => {},
};

function MapCorner() {
  return (
    <MapRuntimeContext.Provider value={NO_MAP}>
      <div style={{ position: 'relative', width: FRAME_WIDTH_PX, height: FRAME_HEIGHT_PX }}>
        <MapControlButtons onLocate={() => {}} />
      </div>
    </MapRuntimeContext.Provider>
  );
}

const meta = {
  title: 'Map/MapControlButtons',
  parameters: {
    docs: {
      description: {
        component:
          'Locate-me and the zoom pair, in place of MapLibre’s own controls, sized for a thumb ' +
          'and themed like the rest of the map chrome. Shown in the map’s bottom-right corner.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Desktop: Story = { render: () => <MapCorner /> };

export const Phone: Story = {
  render: () => <MapCorner />,
  globals: { viewport: { value: MOBILE_VIEWPORT, isRotated: false } },
};
