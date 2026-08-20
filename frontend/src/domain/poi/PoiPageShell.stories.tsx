import type { Meta, StoryObj } from '@storybook/react-vite';
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
} from './PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from './PoiPageShell';
import { Button } from '@ui';

// The shell is the interesting subject, not any one type: the stories exist to show
// that the SAME markup is the drawer and the routed page, and that a type with
// fewer blocks produces a shorter page rather than a page with holes in it.
const meta = {
  title: 'POI/PoiPageShell',
  component: PoiPageShell,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Every pin opens the same page; the type decides which blocks appear. ' +
          'The shell renders whatever it is given in one fixed order (see ' +
          '`blocks.ts`), draws a hairline only between groups that both have ' +
          'content, and marks the fold between "can I stay here, and when" and ' +
          '"tell me more". `variant` is the only difference between the map ' +
          'drawer and the routed detail page.',
      },
    },
  },
} satisfies Meta<typeof PoiPageShell>;

export default meta;
type Story = StoryObj<typeof meta>;

const campgroundBlocks: PoiBlockSlots = {
  identity: (
    <PoiIdentity
      eyebrow="Campground · Oregon State Parks"
      title="Jasper State Recreation Site"
      subtitle="85581 Jasper Park Rd, Pleasant Hill, OR 97455"
      verdict={<span className="rt-poi-verdict-tone rt-poi-verdict-tone--open">Year-round</span>}
    />
  ),
  actions: (
    <PoiActions>
      <Button variant="primary">Directions</Button>
      <Button variant="secondary">Watch dates</Button>
    </PoiActions>
  ),
  glance: (
    <PoiGlance
      tags={[
        { label: 'Water' },
        { label: 'No showers', absent: true },
        { label: 'Flush toilets' },
        { label: 'Pets allowed' },
      ]}
    />
  ),
  gettingThere: (
    <PoiProse heading="Getting there">
      <p>From Eugene, take Highway 126 east toward Springfield and follow signs to Jasper.</p>
    </PoiProse>
  ),
  goodToKnow: (
    <PoiProse heading="Good to know">
      <p>Open for day use year-round. Group facilities reservable May 1 through September 30.</p>
    </PoiProse>
  ),
  specs: (
    <PoiSpecs
      list={{
        heading: 'Stay details',
        rows: [
          { label: 'Price', value: '$24–$29' },
          { label: 'Check-in', value: '2:00 PM' },
          { label: 'Max RV', value: '40 ft' },
        ],
      }}
    />
  ),
  contact: (
    <PoiContact
      rows={[
        { label: 'Phone', value: '+1 (541) 937-1173' },
        { label: 'Managed by', value: 'Oregon State Parks' },
      ]}
    />
  ),
  links: (
    <PoiLinks links={[{ label: 'Oregon State Parks — Jasper SRS', href: 'https://example.test' }]} />
  ),
  nearby: (
    <PoiNearby
      heading="Parks nearby"
      items={[
        { id: '1', name: 'Elijah Bristow State Park', meta: '2 mi · State park', status: 'Day use only' },
        { id: '2', name: 'Dexter State Recreation Site', meta: '5 mi · State park', status: 'Day use only' },
        { id: '3', name: 'Fall Creek State Recreation Area', meta: '12 mi · State park', status: '7 nights open' },
      ]}
    />
  ),
  verified: <PoiVerifiedStamp verified={{ date: '23 May', stale: true }} />,
  provenance: (
    <PoiProvenance>
      <PoiSpecs list={{ heading: 'Source metadata', rows: [{ label: 'Data source', value: 'Oregon State Parks' }] }} />
    </PoiProvenance>
  ),
};

/** The reference type: a reservable campground, which is the only one that uses
 *  every block. Rendered at the map drawer's width. */
export const CampgroundPanel: Story = {
  args: { variant: 'panel', blocks: campgroundBlocks },
};

/** The same blocks at page width, with the one step up that the drawer has no room
 *  for. A parent with a page is a link; one without is text. */
export const CampgroundPage: Story = {
  args: {
    variant: 'page',
    parent: { label: 'Willamette Valley', href: '#' },
    blocks: campgroundBlocks,
  },
};

/** A dropped pin: the same page with almost everything absent, which is the point —
 *  it never looks broken. */
export const DroppedPin: Story = {
  args: {
    variant: 'panel',
    blocks: {
      identity: (
        <PoiIdentity
          eyebrow="Dropped pin"
          title="37.7449, −119.5933"
          subtitle="Yosemite National Park · no matching place"
        />
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
              { label: 'Elevation', value: '4,035 ft' },
            ],
          }}
        />
      ),
    },
  },
};
