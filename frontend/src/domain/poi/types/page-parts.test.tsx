import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { render } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { useMapStore } from '@/stores/mapStore';
import type { PoiPageParts } from './common';
import { poiPageFor } from './registry';

const POI_COORDINATES: [number, number] = [-119.5383, 37.8651];

const USER_LOCATION_FAR_FROM_THE_PIN = { lng: -122.4194, lat: 37.7749 };

const DISTANCE_UNITS = /\d\s?(km|mi)\b/i;

const EYEBROW_SELECTOR = '.rt-poi-eyebrow';

const feature = (properties: Record<string, unknown>): FlatPoiFeature => ({
  type: 'Feature',
  id: 7,
  geometry: { type: 'Point', coordinates: POI_COORDINATES },
  properties,
});

function partsOf(properties: Record<string, unknown>): PoiPageParts {
  const Page = poiPageFor(properties);
  if (!Page) throw new Error('no page for this category');
  let captured: PoiPageParts | null = null;
  render(
    <Page
      variant="panel"
      feature={feature(properties)}
      render={(parts) => {
        captured = parts;
        return null;
      }}
    />,
  );
  if (captured === null) throw new Error('the type never handed its parts over');
  return captured;
}

const CAMPGROUND = {
  category: 'campground',
  name: 'Upper Pines',
  parent_name: 'Yosemite National Park',
  agency: 'National Park Service',
  state: 'CA',
};

const CHARGER = {
  category: 'tesla_supercharger',
  name: 'Mariposa Supercharger',
  street: '5035 Highway 140',
  city: 'Mariposa',
  state: 'CA',
  postcode: '95338',
};

const TRAILHEAD = {
  category: 'trailhead',
  name: 'Mist Trail',
  street: '9000 Happy Isles Loop Rd',
  city: 'Yosemite Valley',
  state: 'CA',
};

const STATE_PAGE = { category: 'state', name: 'California' };

const DROPPED_PIN = { category: 'dropped-pin' };

const PARK_GROUP = {
  category: 'park-group',
  name: 'Yosemite',
  State_Nm: 'California',
  Mang_Name: 'NPS',
};

beforeEach(() => {
  useMapStore.setState({ userLocation: USER_LOCATION_FAR_FROM_THE_PIN });
});

afterEach(() => {
  useMapStore.setState({ userLocation: null });
});

describe('the parts a type hands its render prop', () => {
  test.each([
    ['campground', CAMPGROUND, 'Upper Pines', 'Campground · National Park Service'],
    ['charger', CHARGER, 'Mariposa Supercharger', 'Charger · Tesla'],
    ['trailhead', TRAILHEAD, 'Mist Trail', 'Trailhead'],
    ['park group', PARK_GROUP, 'Yosemite', 'National park'],
  ])('%s names itself and states what it is beside the name', (_label, properties, name, subtitle) => {
    expect(partsOf(properties)).toMatchObject({ name, subtitle });
  });

  test.each([
    ['campground', CAMPGROUND],
    ['charger', CHARGER],
    ['trailhead', TRAILHEAD],
    ['state', STATE_PAGE],
    ['dropped pin', DROPPED_PIN],
    ['park group', PARK_GROUP],
  ])('%s hands over the very line its own page prints as the eyebrow', (_label, properties) => {
    const Page = poiPageFor(properties);
    if (!Page) throw new Error('no page for this category');
    const { container } = render(<Page variant="page" feature={feature(properties)} />);

    expect(container.querySelector(EYEBROW_SELECTOR)?.textContent ?? '').toBe(partsOf(properties).subtitle);
  });

  test('a campground names the agency that manages it, not the park it sits in', () => {
    const { subtitle } = partsOf(CAMPGROUND);

    expect(subtitle).toContain(CAMPGROUND.agency);
    expect(subtitle).not.toContain(CAMPGROUND.parent_name);
  });

  test.each([
    ['campground', CAMPGROUND],
    ['charger', CHARGER],
    ['trailhead', TRAILHEAD],
    ['state', STATE_PAGE],
    ['dropped pin', DROPPED_PIN],
    ['park group', PARK_GROUP],
  ])('%s leaves the distance to the verdict', (_label, properties) => {
    expect(partsOf(properties).subtitle).not.toMatch(DISTANCE_UNITS);
  });

  test.each([
    ['a dropped pin', DROPPED_PIN, 'Dropped pin', 'Dropped pin'],
    ['a state', STATE_PAGE, 'California', 'State'],
  ])('%s states its kind', (_label, properties, name, subtitle) => {
    expect(partsOf(properties)).toMatchObject({ name, subtitle });
  });

  test('every type still hands over the blocks it built', () => {
    expect(Object.keys(partsOf(CAMPGROUND).blocks)).toContain('identity');
  });
});
