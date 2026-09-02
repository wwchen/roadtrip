import type { Meta, StoryObj } from '@storybook/react-vite';
import type { FlatPoiFeature } from '@/lib/poi';
import { poiPageFor } from './registry';

// One story per POI type, driven through the real registry.
//
// Three of these types have live data (campground, charger, gym); the rest do not
// exist in any dataset yet, and this is where you see what their page will be when
// they do. Every story goes through `poiPageFor` rather than importing a component
// directly, so a type that falls out of the registry disappears from the catalog
// instead of quietly rendering anyway.
const meta = {
  title: 'POI/Types',
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'Every pin opens the same page. Each of these supplies a different subset ' +
          'of the thirteen blocks, and nothing else about the page changes — the ' +
          'block after the rule is the one thing that type is *for*.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const feature = (properties: Record<string, unknown>): FlatPoiFeature => ({
  type: 'Feature',
  id: 1,
  geometry: { type: 'Point', coordinates: [-119.5933, 37.7449] },
  properties,
});

/** Renders whatever the registry says this category gets, at page width. */
function TypePage({ properties }: { properties: Record<string, unknown> }) {
  const f = feature(properties);
  const Page = poiPageFor(properties);
  if (!Page) return <p>No page registered for “{String(properties.category)}”.</p>;
  return <Page feature={f} variant="page" />;
}

/** The reference type. The availability grid is a feature component, so it is
 *  stood in for here — everything else is the real page. */
export const Campground: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'campground',
        name: 'Jasper State Recreation Site',
        agency: 'Oregon State Parks',
        parent_name: 'Willamette Valley',
        state: 'Oregon',
        season: 'year-round',
        reservable: true,
        sites: 24,
        amenities: { water: true, showers: false, toilet_kind: 'flush', pets_allowed: true },
        activities: ['Fishing', 'Hiking'],
        price: { minimum: 24, maximum: 29 },
        schedule: { check_in_time: '14:00' },
        phone: '+1 (541) 937-1173',
        management: 'Oregon State Parks',
        source: 'oregonstateparks',
        source_id: 'or-151',
        last_verified: '2026-05-23',
        links: [{ title: 'Oregon State Parks — Jasper SRS', url: 'https://example.test' }],
        description: '<p>Day-use park on the Middle Fork Willamette River.</p>',
      }}
    />
  ),
};

/** A first-come ground is not a type: it is the campground page with the
 *  availability block absent, because no provider publishes its inventory. */
export const CampgroundFirstCome: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'campground',
        name: 'Camp 4',
        agency: 'National Park Service',
        parent_name: 'Yosemite National Park',
        state: 'California',
        reservable: false,
        sites: 36,
        availability_supported: false,
        amenities: { water: true, showers: false },
      }}
    />
  ),
};

/** Busy hours takes the availability slot: "when is it full" at the resolution a
 *  charger has. No nightly grid, no stay details. */
export const Charger: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'tesla_supercharger',
        name: 'Sutherlin, OR',
        street: '116 Clover Leaf Loop',
        city: 'Sutherlin',
        state: 'OR',
        postcode: '97479',
        stallCount: 51,
        powerKilowatt: 250,
        v3: 51,
        dateOpened: '2021-06-14',
        detailPayload: {
          accessHours: { twentyFourSeven: true },
          openToNonTeslas: true,
          amenities: ['restrooms', 'shopping'],
        },
      }}
    />
  ),
};

/** The parent of its campgrounds. The rollup that counts children instead of sites
 *  is the one thing this type is for, and no endpoint returns it yet. */
export const Park: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'national-park',
        Unit_Nm: 'Yosemite National Park',
        State_Nm: 'California',
        Mang_Name: 'National Park Service',
        GIS_Acres: 761747,
      }}
    />
  ),
};

export const StatePark: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'state-park',
        Unit_Nm: 'Silver Falls State Park',
        State_Nm: 'Oregon',
        Mang_Name: 'Oregon Parks and Recreation Department',
        GIS_Acres: 9200,
        description:
          'Ten waterfalls on one 7.2-mile loop, four of them with a trail behind the ' +
          'falls. The day-use area fills by mid-morning on summer weekends.',
      }}
    />
  ),
};

export const Trailhead: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'trailhead',
        name: 'Mist Trail',
        city: 'Happy Isles',
        state: 'CA',
        website: 'https://example.test',
        trail_distance: '5.4 mi round trip',
        trail_gain: '2,000 ft',
        trail_parking: 'Fills by 8am',
        trail_permit: 'None for day use',
      }}
    />
  ),
};

export const TownStop: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'town',
        name: 'Mariposa, CA',
        city: 'Mariposa',
        state: 'CA',
        fuel: '4 stations',
        groceries: 'Pioneer Market, to 9pm',
        cell_service: 'All carriers',
        last_stop: 'Before Hwy 140 canyon',
      }}
    />
  ),
};

/** The same page with almost everything absent, which is the point — it never looks
 *  broken. */
export const DroppedPin: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'dropped-pin',
        name: '37.7449, −119.5933',
        inside_region: 'Yosemite National Park',
        nearest_place: 'Upper Pines, 1.2 mi',
        elevation: '4,035 ft',
      }}
    />
  ),
};

/** One level above the park. Same skeleton, one row per park instead of per
 *  campground — that table is the block this type is waiting on. */
export const State: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'state',
        name: 'Oregon',
        unit_count: '254',
        camping_unit_count: '47',
        agencies: 'Oregon State Parks · National Park Service · US Forest Service',
      }}
    />
  ),
};

/** OSM-imported, so the record is sparse — and renders sparse rather than as a page
 *  of empty headings. `brand` names the button, so no chain is hard-coded. */
export const Gym: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'planet_fitness_location',
        name: 'Planet Fitness',
        brand: 'Planet Fitness',
        street: '1205 Southeast 16th Court',
        city: 'Ankeny',
        state: 'IA',
        postcode: '50021',
        opening_hours: 'Mo-Fr 05:00-22:00',
        phone: '+1 515-555-0113',
        website: 'https://www.planetfitness.com/gyms/ankeny-ia',
      }}
    />
  ),
};

/** The common case, not the exception: most OSM gym elements tag no website and no
 *  hours, so the page is a name, an address and a way to get there. */
export const GymSparse: Story = {
  render: () => (
    <TypePage
      properties={{
        category: 'planet_fitness_location',
        name: 'Planet Fitness',
        brand: 'Planet Fitness',
        street: '1205 Southeast 16th Court',
        city: 'Ankeny',
        state: 'IA',
      }}
    />
  ),
};
