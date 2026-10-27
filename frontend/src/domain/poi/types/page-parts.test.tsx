import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { render } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { useMapStore } from '@/stores/mapStore';
import type { PoiPageParts } from './common';
import { poiPageFor } from './registry';

const POI_COORDINATES: [number, number] = [-119.5383, 37.8651];

/**
 * Far enough from the pin that `useDistanceTo` resolves a real distance, so a
 * subtitle that let one through would print it.
 */
const USER_LOCATION = { lng: -122.4194, lat: 37.7749 };

const DISTANCE_UNITS = /\d\s?(km|mi)\b/i;

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
  useMapStore.setState({ userLocation: USER_LOCATION });
});

afterEach(() => {
  useMapStore.setState({ userLocation: null });
});

describe('the parts a type hands its render prop', () => {
  test.each([
    ['campground', CAMPGROUND, 'Upper Pines', 'Campground · Yosemite National Park'],
    ['charger', CHARGER, 'Mariposa Supercharger', '5035 Highway 140, Mariposa, CA 95338'],
    ['trailhead', TRAILHEAD, 'Mist Trail', '9000 Happy Isles Loop Rd, Yosemite Valley, CA'],
    ['park group', PARK_GROUP, 'Yosemite', 'California · NPS'],
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
  ])('%s leaves the distance to the verdict', (_label, properties) => {
    expect(partsOf(properties).subtitle).not.toMatch(DISTANCE_UNITS);
  });

  test('a dropped pin with no address is named by its point', () => {
    expect(partsOf(DROPPED_PIN)).toMatchObject({
      name: 'Dropped pin',
      subtitle: '37.86510, -119.53830',
    });
  });

  test('a place that is not a pin shows no secondary line rather than a decimal pair', () => {
    expect(partsOf(STATE_PAGE)).toMatchObject({ name: 'California', subtitle: '' });
  });

  test('every type still hands over the blocks it built', () => {
    expect(Object.keys(partsOf(CAMPGROUND).blocks)).toContain('identity');
  });
});
