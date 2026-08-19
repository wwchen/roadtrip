import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { ParkPoiPage } from './park';

const park = (properties: Record<string, unknown>): FlatPoiFeature => ({
  type: 'Feature',
  id: 1,
  geometry: { type: 'Point', coordinates: [-119.5933, 37.7449] },
  properties,
});

const crumbs = () =>
  [...screen.getByLabelText('Breadcrumb').querySelectorAll('li')].map((li) => li.textContent);

describe('the park page’s ancestry', () => {
  // The step above a national park is the system that lists it, not the state it
  // happens to sit in: the agency is what changes the booking site, and a park
  // spanning a state line has one system and two states.
  test('a national park sits under the park system', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({
          category: 'national-park',
          Unit_Nm: 'Yosemite National Park',
          State_Nm: 'California',
        })}
      />,
    );

    expect(crumbs()).toEqual(['National parks', 'Yosemite National Park']);
  });

  // A state park's parent IS its state — that is the 4e page, and the one thing
  // that lists it.
  test('a state park sits under its state', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({
          category: 'state-park',
          Unit_Nm: 'Silver Falls State Park',
          State_Nm: 'Oregon',
        })}
      />,
    );

    expect(crumbs()).toEqual(['Oregon', 'Silver Falls State Park']);
  });

  // Neither group page exists yet, so every step is text. A crumb that renders as a
  // link and does nothing is worse than one that does not claim to be one.
  test('no step links anywhere yet', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({ category: 'national-park', Unit_Nm: 'Acadia National Park', State_Nm: 'Maine' })}
      />,
    );

    expect(screen.getByLabelText('Breadcrumb').querySelectorAll('a')).toHaveLength(0);
  });

  // The drawer has no room for a trail, and the map is already the context.
  test('the panel shows no trail at all', () => {
    render(
      <ParkPoiPage
        variant="panel"
        feature={park({ category: 'national-park', Unit_Nm: 'Zion National Park', State_Nm: 'Utah' })}
      />,
    );

    expect(screen.queryByLabelText('Breadcrumb')).toBeNull();
  });
});
