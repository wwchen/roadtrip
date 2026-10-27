import { useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { dayFull, dayOpen, summary } from '@/domain/place/stay-fixtures';
import {
  PoiActions,
  PoiContact,
  PoiGlance,
  PoiIdentity,
  PoiLinks,
  PoiNearby,
  PoiProse,
  PoiProvenance,
  PoiSpecs,
  PoiVerifiedStamp,
} from '@/domain/poi/PoiBlocks';
import type { PoiBlockSlots } from '@/domain/poi/PoiPageShell';
import { Button } from '@ui';
import { PlaceSheet, type PlaceSheetProps } from './PlaceSheet';

const PHONE_FRAME: CSSProperties = {
  position: 'relative',
  width: 390,
  height: 760,
  margin: '0 auto',
  overflow: 'hidden',
  border: '1px solid var(--rt-border)',
  borderRadius: 24,
  background: 'linear-gradient(160deg, var(--rt-fill-subtle), var(--rt-bg))',
};

const DESKTOP_FRAME: CSSProperties = { ...PHONE_FRAME, width: 960, height: 640, borderRadius: 12 };

const NAME = 'Upper Pines';
const ORIGIN = { label: 'Oakland', minutes: 232 };
const WEEK = ['2026-11-06', '2026-11-07', '2026-11-08', '2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12'];
const OPEN_SITES = [4, 6, 2, 3, 5, 1, 2];
const SHUT_NIGHTS = new Set([WEEK[2], WEEK[3]]);
const LAST_SITE = 1;
const REOPENS_ON = '2027-05-14';

const noop = () => undefined;

const openWeek = WEEK.map((date, index) => dayOpen(date, OPEN_SITES[index] ?? 1));
const twoShutWeek = WEEK.map((date, index) =>
  SHUT_NIGHTS.has(date) ? dayFull(date) : dayOpen(date, OPEN_SITES[index] ?? 1),
);
const oneLeftWeek = WEEK.map((date, index) => (index === 0 ? dayOpen(date, LAST_SITE) : dayFull(date)));
const shutWeek = WEEK.map((date) => dayFull(date));

const SITE_NAMES: ReadonlyMap<number, string> = new Map([
  [1, 'Loop A / 12'],
  [2, 'Loop A / 14'],
  [3, 'Loop B / 03'],
  [4, 'Loop B / 07'],
  [5, 'Loop C / 21'],
  [6, 'Loop C / 24'],
]);

const campgroundBlocks: PoiBlockSlots = {
  identity: (
    <PoiIdentity
      eyebrow="Campground · Yosemite National Park"
      title={NAME}
      subtitle="9000 Upper Pines Rd, Yosemite Valley, CA 95389"
    />
  ),
  actions: (
    <PoiActions>
      <Button variant="primary">Reserve</Button>
      <Button variant="secondary">Directions</Button>
    </PoiActions>
  ),
  availability: (
    <PoiProse heading="Season">
      <p>Open year-round. Reservations open five months ahead, on the 15th at 7am Pacific.</p>
    </PoiProse>
  ),
  glance: (
    <PoiGlance
      tags={[{ label: 'Flush toilets' }, { label: 'Drinking water' }, { label: 'No showers', absent: true }]}
    />
  ),
  gettingThere: (
    <PoiProse heading="Getting there">
      <p>From the valley loop, follow signs past Curry Village; the entrance is on the right.</p>
    </PoiProse>
  ),
  goodToKnow: (
    <PoiProse heading="Good to know">
      <p>Bear boxes at every site, and food must be stored in them overnight.</p>
    </PoiProse>
  ),
  specs: (
    <PoiSpecs
      list={{
        heading: 'Stay details',
        rows: [
          { label: 'Price', value: '$36' },
          { label: 'Check-in', value: '12:00 PM' },
          { label: 'Max RV', value: '35 ft' },
        ],
      }}
    />
  ),
  contact: <PoiContact rows={[{ label: 'Phone', value: '+1 (209) 372-0200' }]} />,
  links: <PoiLinks links={[{ label: 'Recreation.gov — Upper Pines', href: 'https://example.test' }]} />,
  nearby: (
    <PoiNearby
      heading="Campgrounds nearby"
      items={[
        { id: '1', name: 'Lower Pines', meta: '0.3 mi · Campground', status: '2 nights open' },
        { id: '2', name: 'North Pines', meta: '0.5 mi · Campground', status: 'Full' },
      ]}
    />
  ),
  verified: <PoiVerifiedStamp verified={{ date: '23 May', stale: true }} />,
  provenance: (
    <PoiProvenance>
      <PoiSpecs list={{ heading: 'Source metadata', rows: [{ label: 'Data source', value: 'Recreation.gov' }] }} />
    </PoiProvenance>
  ),
};

const droppedPinBlocks: PoiBlockSlots = {
  identity: (
    <PoiIdentity eyebrow="Dropped pin" title="37.7449, −119.5933" subtitle="Yosemite National Park · no matching place" />
  ),
  actions: (
    <PoiActions>
      <Button variant="primary">Directions</Button>
    </PoiActions>
  ),
  specs: (
    <PoiSpecs
      list={{
        heading: 'What we know',
        rows: [
          { label: 'Inside', value: 'Yosemite National Park' },
          { label: 'Nearest', value: 'Upper Pines, 1.2 mi' },
        ],
      }}
    />
  ),
};

function Live(props: PlaceSheetProps) {
  const [selected, setSelected] = useState(props.nights.selected);
  return <PlaceSheet {...props} nights={{ ...props.nights, selected, onSelect: setSelected }} />;
}

const meta = {
  title: 'Place/PlaceSheet',
  component: PlaceSheet,
  render: (args) => <Live {...args} />,
  args: {
    name: NAME,
    arrival: 'from-map',
    verdict: {
      name: NAME,
      answer: summary({ status: 'open', open_sites: 2 }),
      origin: ORIGIN,
      priceFrom: 'From $36',
      fits: 'Fits 6',
    },
    nights: { days: openWeek, dates: WEEK, selected: WEEK[0], onSelect: noop },
    siteNames: SITE_NAMES,
    blocks: campgroundBlocks,
  },
  decorators: [
    (Story, { parameters }) => (
      <div style={parameters.desktop ? DESKTOP_FRAME : PHONE_FRAME}>
        <Story />
      </div>
    ),
  ],
  parameters: {
    layout: 'centered',
    docs: {
      description: {
        component:
          'The Place screen: one sheet, three heights, and a fact at exactly one of them. ' +
          'Peek answers whether to go — the shared `PlaceVerdict`, which is also the Where ' +
          "card. Half answers which nights, what it costs and what to do about a shut one. " +
          'Full answers what it is like on arrival. Dragging up adds; the header compacts ' +
          'from half, so nothing is read twice. ' +
          'Arriving from the map opens at Half, because Peek is the card the user just ' +
          'tapped; `arrival="cold"` — a notification, a deep link, a place reached by name — ' +
          'opens at Peek. ' +
          'A stay claim comes only from the stay check behind the verdict; the strip reads ' +
          "the per-day grid, and neither stands in for the other. " +
          'The POI blocks render in `blocks.ts` order, split at `RULE_BEFORE_GROUP`: the ' +
          'groups above it at Half, the rest and the footer at Full, so the fold that used ' +
          'to be a hairline is now the detent boundary. A group with nothing in it draws no ' +
          'band and no rule, which is what a dropped pin relies on. ' +
          'The sheet is `position: absolute`, so its container must be positioned — the ' +
          'phone frame these stories draw is the contract, not decoration.',
      },
    },
  },
} satisfies Meta<typeof PlaceSheet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Open: Story = {};

export const FewLeft: Story = {
  args: {
    verdict: { ...meta.args.verdict, answer: summary({ status: 'few_left', open_sites: 1 }) },
    nights: { ...meta.args.nights, days: oneLeftWeek, selected: WEEK[0] },
  },
};

export const Full: Story = {
  args: {
    verdict: { ...meta.args.verdict, answer: summary({ status: 'full', open_sites: 0 }) },
    nights: { ...meta.args.nights, days: shutWeek, selected: WEEK[2] },
    alert: { night: WEEK[2], gate: 'ready', onCreate: noop },
  },
};

export const NoData: Story = {
  args: {
    verdict: { ...meta.args.verdict, answer: summary({ status: 'no_data', open_sites: 0 }) },
    nights: { ...meta.args.nights, days: [], selected: null },
  },
};

export const ClosedForSeason: Story = {
  args: {
    verdict: {
      ...meta.args.verdict,
      answer: summary({ status: 'closed_for_season', open_sites: 0 }),
      reopensOn: REOPENS_ON,
    },
    nights: { ...meta.args.nights, days: shutWeek, selected: null },
  },
};

export const OneShutNightInAnOpenWeek: Story = {
  args: {
    nights: { ...meta.args.nights, days: twoShutWeek, selected: WEEK[2] },
    alert: { night: WEEK[2], gate: 'ready', onCreate: noop },
  },
};

export const ColdArrival: Story = { args: { arrival: 'cold' } };

export const DroppedPin: Story = {
  args: {
    name: 'Dropped pin',
    verdict: {
      name: 'Dropped pin',
      answer: summary({ status: 'no_data', open_sites: 0 }),
      origin: ORIGIN,
    },
    nights: { ...meta.args.nights, days: [], selected: null },
    siteNames: undefined,
    blocks: droppedPinBlocks,
  },
};

export const Desktop: Story = { parameters: { desktop: true } };
