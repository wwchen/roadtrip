import { useState, type ReactNode } from 'react';
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
import './gallery.css';

const REGION_OPTIONS = [
  { value: 'pacific', label: 'Pacific Northwest' },
  { value: 'mountain', label: 'Mountain West' },
];

const TABLE_COLUMNS = [
  { key: 'component', label: 'Component' },
  { key: 'state', label: 'State' },
  { key: 'purpose', label: 'Purpose' },
];

export function GalleryPage() {
  const [secret, setSecret] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);

  return (
    <main className="gallery-page shell">
      <header className="gallery-hero">
        <div>
          <p className="gallery-kicker">Roadtrip design system</p>
          <h1>UI Gallery</h1>
          <p className="gallery-intro">
            Production LDS primitives and Roadtrip additions, rendered through the same theme,
            providers, and CSS used by the app.
          </p>
        </div>
        <Button href="/" variant="secondary">Back to map</Button>
      </header>

      <GallerySection title="Actions and status" description="Primary hierarchy, compact metadata, and feedback states.">
        <div className="gallery-stack">
          <ButtonGroup stackOnMobile>
            <Button variant="primary">Plan a trip</Button>
            <Button variant="secondary">Save draft</Button>
            <Button variant="tertiary">Cancel</Button>
          </ButtonGroup>
          <div className="gallery-inline">
            <Tag status="success">Available</Tag>
            <Tag status="warning">Limited</Tag>
            <Tag status="error">Unavailable</Tag>
            <Chip selected>Campgrounds</Chip>
            <Chip>Superchargers</Chip>
          </div>
          <Banner status="info" title="Visual review surface">
            Changes here should match the same component in the production pages.
          </Banner>
          <Inline status="success">All frontend checks passed.</Inline>
        </div>
      </GallerySection>

      <GallerySection title="Inputs" description="These controls intentionally use LDS's uncontrolled input contract.">
        <div className="gallery-form-grid">
          <TextField id="gallery-trip-name" label="Trip name" defaultValue="Pacific coast" help="Shown to collaborators." />
          <Select id="gallery-region" label="Region" defaultValue="pacific" options={REGION_OPTIONS} />
          <Textarea id="gallery-notes" label="Notes" defaultValue="Bring the rain fly." rows={3} showCount maxLength={120} />
          <CodeField id="gallery-code" label="Access code" defaultValue="246810" length={6} groupAfter={3} />
        </div>
        <div className="gallery-inline gallery-controls">
          <Checkbox label="Tent camping" defaultChecked />
          <Radio name="pace" label="Relaxed pace" defaultChecked />
          <Radio name="pace" label="Fast pace" />
          <Toggle label="Availability alerts" defaultChecked />
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
      </GallerySection>

      <GallerySection title="Containers and data" description="Composition patterns used by dashboards, drawers, and settings.">
        <div className="gallery-card-grid">
          <Card
            kicker="Campground"
            title="Bowman Bay"
            body="12 sites · Open May–September"
            meta={<Tag status="success">Open</Tag>}
            actions={<Button size="sm" variant="secondary">View details</Button>}
          />
          <Card
            kicker="Route"
            title="Sea to Sky"
            body="Two stops and one ferry crossing."
            emphasis="strong"
          />
        </div>
        <div className="gallery-rows">
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
      </GallerySection>

      <GallerySection title="Roadtrip additions" description="Local components that encode app-specific safety and privacy rules.">
        <div className="gallery-form-grid">
          <SecretField
            id="gallery-secret"
            label="Slack token"
            hint="9x2a"
            help="Stored secrets are never read back into the browser."
            value={secret}
            onChange={setSecret}
          />
          <div className="gallery-confirm">
            <ConfirmButton
              variant="secondary"
              hue="red"
              label="Disconnect Slack"
              onConfirm={() => setConfirmed(true)}
            />
            {confirmed ? <Inline status="success">Confirmation callback fired.</Inline> : null}
          </div>
        </div>
      </GallerySection>

      <GallerySection title="Loading and empty states" description="Stable placeholders for asynchronous surfaces.">
        <div className="gallery-card-grid">
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
      </GallerySection>
    </main>
  );
}

function GallerySection({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <section className="gallery-section" aria-labelledby={`gallery-${title.toLowerCase().replaceAll(' ', '-')}`}>
      <div className="gallery-section-head">
        <h2 id={`gallery-${title.toLowerCase().replaceAll(' ', '-')}`}>{title}</h2>
        <p>{description}</p>
      </div>
      <div className="gallery-surface">{children}</div>
    </section>
  );
}
