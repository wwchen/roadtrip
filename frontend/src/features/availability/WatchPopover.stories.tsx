import type { Meta, StoryObj } from '@storybook/react-vite';
import type { WatchCapabilities as WireWatchCapabilities } from '@/api/availability-api';
import type { Watch } from '@/api/watches-api';
import { TRIGGER_KIND_ATC, TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_SLACK_NOTIFY } from '@/lib/watch-triggers';
import { normalizeWatchCapabilities } from '@/lib/watch-windows';
import { StoryAnchoredCell } from './StoryAnchoredCell';
import { STORY_POI_NAME, STORY_WATCH, STORY_WATCHED_DATE } from './story-fixtures';
import { WatchPopover } from './WatchPopover';
import './availability.css';

const FRAME_HEIGHT_PX = 520;
const BOOKING_SYSTEM = 'Recreation.gov';

const NOTIFY_ONLY: WireWatchCapabilities = {
  trigger_kinds: [TRIGGER_KIND_SLACK_NOTIFY, TRIGGER_KIND_EMAIL_NOTIFY],
  add_to_cart: { state: 'unsupported' },
};

const WITH_CART: WireWatchCapabilities = {
  trigger_kinds: [TRIGGER_KIND_SLACK_NOTIFY, TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_ATC],
  add_to_cart: { state: 'ready', provider: 'recgov', provider_display: BOOKING_SYSTEM },
};

const CART_NEEDS_LOGIN: WireWatchCapabilities = {
  trigger_kinds: [TRIGGER_KIND_SLACK_NOTIFY, TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_ATC],
  add_to_cart: { state: 'no_credentials', provider: 'recgov', provider_display: BOOKING_SYSTEM },
};

const EMAIL_ONLY: WireWatchCapabilities = {
  trigger_kinds: [TRIGGER_KIND_EMAIL_NOTIFY],
  add_to_cart: { state: 'unsupported' },
};

const UNKNOWN: WireWatchCapabilities = { trigger_kinds: [], add_to_cart: { state: 'unsupported' } };

const EXISTING: Watch = {
  ...STORY_WATCH,
  trigger_kinds: [TRIGGER_KIND_SLACK_NOTIFY, TRIGGER_KIND_ATC],
};

interface PopoverProps {
  capabilities: WireWatchCapabilities;
  watch?: Watch;
  gate?: 'signed-out';
}

function Popover({ capabilities, watch, gate }: PopoverProps) {
  return (
    <StoryAnchoredCell label={watch ? 'R' : 'FF'} minHeight={FRAME_HEIGHT_PX}>
      {(anchor) => (
        <WatchPopover
          anchor={anchor}
          poiName={STORY_POI_NAME}
          date={STORY_WATCHED_DATE}
          watch={watch}
          capabilities={normalizeWatchCapabilities(capabilities)}
          gate={gate}
          onSignIn={() => {}}
          onOpenSettings={() => {}}
          onSave={async () => {}}
          onRemove={async () => {}}
          onClose={() => {}}
        />
      )}
    </StoryAnchoredCell>
  );
}

const meta = {
  title: 'Availability/WatchPopover',
  parameters: {
    docs: {
      description: {
        component:
          'The watch editor as the grid opens it: portalled to the body and positioned against ' +
          'the cell that was clicked. What it offers follows the capability block the week ' +
          'carried, and a signed-out visitor gets the sign-in gate in its place.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const NewWatch: Story = { render: () => <Popover capabilities={NOTIFY_ONLY} /> };

export const ExistingWatch: Story = { render: () => <Popover capabilities={WITH_CART} watch={EXISTING} /> };

export const WithAddToCart: Story = { render: () => <Popover capabilities={WITH_CART} /> };

export const CartNeedsCredentials: Story = { render: () => <Popover capabilities={CART_NEEDS_LOGIN} /> };

export const EmailOnly: Story = { render: () => <Popover capabilities={EMAIL_ONLY} /> };

export const CapabilitiesUnknown: Story = { render: () => <Popover capabilities={UNKNOWN} /> };

export const SignedOut: Story = {
  render: () => <Popover capabilities={NOTIFY_ONLY} gate="signed-out" />,
};
