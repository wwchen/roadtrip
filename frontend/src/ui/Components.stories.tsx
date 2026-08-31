import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  Banner,
  Button,
  ButtonGroup,
  Card,
  Checkbox,
  Chip,
  CodeField,
  ConfirmButton,
  EmptyState,
  Inline,
  LinkButton,
  Radio,
  Row,
  SecretField,
  SegmentedControl,
  Select,
  Skeleton,
  Table,
  Tag,
  Textarea,
  TextField,
  Toggle,
  Tooltip,
} from '@ui';

const REGION_OPTIONS = [
  { value: 'pacific', label: 'Pacific Northwest' },
  { value: 'mountain', label: 'Mountain West' },
];

const TABLE_COLUMNS = [
  { key: 'component', label: 'Component' },
  { key: 'state', label: 'State' },
  { key: 'purpose', label: 'Purpose' },
];

const meta = {
  title: 'Design System/Component Catalog',
  parameters: {
    docs: {
      description: {
        component: 'LDS primitives and Roadtrip additions rendered through the production @ui boundary and theme.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActionsAndStatus: Story = {
  render: () => (
    <div className="rt-storybook-stack">
      <ButtonGroup stackOnMobile>
        <Button variant="primary">Plan a trip</Button>
        <Button variant="secondary">Save draft</Button>
        <Button variant="tertiary">Cancel</Button>
      </ButtonGroup>
      <div className="rt-storybook-inline">
        <Tag status="success">Available</Tag>
        <Tag status="warning">Limited</Tag>
        <Tag status="error">Unavailable</Tag>
        <Chip selected>Campgrounds</Chip>
        <Chip>Superchargers</Chip>
      </div>
      <Banner status="info" title="Storybook review surface">
        Component changes should match the same states in production pages.
      </Banner>
      <Inline status="success">All frontend checks passed.</Inline>
    </div>
  ),
};

export const Inputs: Story = {
  render: () => (
    <div className="rt-storybook-stack">
      <div className="rt-storybook-grid">
        <TextField id="story-trip-name" label="Trip name" defaultValue="Pacific coast" help="Shown to collaborators." />
        <Select id="story-region" label="Region" defaultValue="pacific" options={REGION_OPTIONS} />
        <Textarea id="story-notes" label="Notes" defaultValue="Bring the rain fly." rows={3} showCount maxLength={120} />
        <CodeField id="story-code" label="Access code" defaultValue="246810" length={6} groupAfter={3} />
      </div>
      <div className="rt-storybook-inline">
        <Checkbox label="Tent camping" defaultChecked />
        <Radio name="pace" label="Relaxed pace" defaultChecked />
        <Radio name="pace" label="Fast pace" />
        <Toggle label="Availability alerts" aria-label="Availability alerts" defaultChecked />
      </div>
      <SegmentedControl
        label="Map detail"
        defaultValue="standard"
        options={[
          { value: 'quiet', label: 'Quiet' },
          { value: 'standard', label: 'Standard' },
          { value: 'dense', label: 'Dense' },
        ]}
      />
    </div>
  ),
};

export const ContainersAndData: Story = {
  render: () => (
    <div className="rt-storybook-stack">
      <div className="rt-storybook-grid">
        <Card
          kicker="Campground"
          title="Bowman Bay"
          body="12 sites · Open May–September"
          meta={<Tag status="success">Open</Tag>}
          actions={<Button size="sm" variant="secondary">View details</Button>}
        />
        <Card kicker="Route" title="Sea to Sky" body="Two stops and one ferry crossing." emphasis="strong" />
      </div>
      <div className="rt-storybook-rows">
        <Row title="Notifications" subtitle="Slack connected" trail={<Tag status="success">On</Tag>} chevron />
        <Row title="Account" subtitle="Profile and sign-in" chevron />
      </div>
      <Table
        columns={TABLE_COLUMNS}
        rows={[
          { component: 'Availability grid', state: <Tag status="success">Live</Tag>, purpose: 'Campground drawer' },
          { component: 'Watch editor', state: <Tag status="info">Ready</Tag>, purpose: 'Alerts' },
        ]}
      />
    </div>
  ),
};

export const RoadtripAdditions: Story = {
  render: () => <RoadtripAdditionsStory />,
};

function RoadtripAdditionsStory() {
  const [secret, setSecret] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);

  return (
    <div className="rt-storybook-grid">
      <SecretField
        id="story-secret"
        label="Slack token"
        hint="9x2a"
        help="Stored secrets are never read back into the browser."
        value={secret}
        onChange={setSecret}
      />
      <div className="rt-storybook-stack">
        <ConfirmButton
          variant="secondary"
          hue="red"
          label="Disconnect Slack"
          onConfirm={() => setConfirmed(true)}
        />
        {confirmed ? <Inline status="success">Confirmation callback fired.</Inline> : null}
        {/* Shown inside prose on purpose: the point of the component is that it
            takes the surrounding sentence's colour and metrics, which a row of
            them on their own would not demonstrate. */}
        <p>
          Couldn&apos;t load sites · <LinkButton>Retry</LinkButton>
        </p>
        <p className="rt-storybook-note">
          checked 4m ago · <LinkButton>refresh</LinkButton>
        </p>
      </div>
    </div>
  );
}

export const LoadingAndEmptyStates: Story = {
  render: () => (
    <div className="rt-storybook-stack">
      <div className="rt-storybook-grid">
        <Card title="Loading campground">
          <Skeleton variant="title" />
          <Skeleton variant="text" />
          <Skeleton variant="text" last />
        </Card>
        <EmptyState
          title="No watches yet"
          body="Create a watch from a campground's availability calendar."
          actions={<Button variant="primary">Browse campgrounds</Button>}
        />
      </div>
      <Tooltip label="Uses the LDS controller-backed tooltip">
        <Button variant="tertiary">Hover for help</Button>
      </Tooltip>
    </div>
  ),
};
