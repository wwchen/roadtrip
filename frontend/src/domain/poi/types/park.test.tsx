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

/** The same park, but as the polygon PAD-US actually ships. */
const mappedPark = (properties: Record<string, unknown>): FlatPoiFeature => ({
  ...park(properties),
  geometry: {
    type: 'Polygon',
    coordinates: [
      [
        [-119.6, 37.7],
        [-119.5, 37.7],
        [-119.5, 37.8],
        [-119.6, 37.7],
      ],
    ],
  },
});

const parentStep = () => screen.getByLabelText('Breadcrumb').textContent;

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

    expect(parentStep()).toBe('National parks');
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

    expect(parentStep()).toBe('Oregon');
  });

  // Every one of these was on screen at once: "Oregon" as the step above, as the
  // subtitle and as a spec row; "State Park" in both the eyebrow and the title.
  test('says nothing twice', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({
          category: 'state-park',
          Unit_Nm: 'Silver Falls State Park',
          State_Nm: 'Oregon',
          Mang_Name: 'Oregon Parks and Recreation Department',
          GIS_Acres: 9200,
        })}
      />,
    );

    // The name already ends in its own type, so the eyebrow has nothing to add.
    expect(document.querySelector('.rt-poi-eyebrow')).toBeNull();
    // The state is the step above, so the subtitle names the agency instead.
    expect(document.querySelector('.rt-poi-subtitle')).toHaveTextContent(
      'Oregon Parks and Recreation Department',
    );
    // …and it is not repeated a third time as a spec row.
    expect(screen.queryByText('State')).toBeNull();
    expect(screen.getByText('9,200 acres')).toBeInTheDocument();
  });

  // A name that does NOT carry its type still earns the word.
  test('a park whose name omits its type keeps the eyebrow', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({ category: 'national-park', Unit_Nm: 'Craters of the Moon', State_Nm: 'Idaho' })}
      />,
    );

    expect(document.querySelector('.rt-poi-eyebrow')).toHaveTextContent('National park');
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
    expect(parentStep()).toBe('National parks');
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

describe('the park page’s body', () => {
  // The photo is promoted onto every category's detail, so a park with one fills
  // the hero slot with the same component the campground page uses.
  test('a park with a photo fills the hero', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({
          category: 'national-park',
          Unit_Nm: 'Yosemite National Park',
          State_Nm: 'California',
          photo_url: 'https://example.test/half-dome.jpg',
        })}
      />,
    );

    const hero = screen.getByRole('img', { name: 'Yosemite National Park' });
    expect(hero).toHaveClass('rt-poi-hero');
    expect(hero).toHaveStyle({ backgroundImage: "url('https://example.test/half-dome.jpg')" });
  });

  // No photo, no band: an empty hero is worse than a page that starts at the title.
  test('a park without a photo has no hero', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({ category: 'national-park', Unit_Nm: 'Zion National Park', State_Nm: 'Utah' })}
      />,
    );

    expect(document.querySelector('.rt-poi-hero')).toBeNull();
  });

  test('the description becomes the good-to-know block', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({
          category: 'state-park',
          Unit_Nm: 'Silver Falls State Park',
          State_Nm: 'Oregon',
          description: '<p>Ten waterfalls on one loop.</p><script>alert(1)</script>',
        })}
      />,
    );

    expect(screen.getByText('Good to know')).toBeInTheDocument();
    expect(screen.getByText('Ten waterfalls on one loop.')).toBeInTheDocument();
    // The prose goes through the upstream sanitiser, so provider markup is words.
    expect(document.querySelector('script')).toBeNull();
  });

  test('a park with no description has no good-to-know block', () => {
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

    expect(screen.queryByText('Good to know')).toBeNull();
  });
});

// Inside-vs-outside is only a fact when the record carries a boundary to be inside
// of. A polygon can be asked; a centroid can only ever say "near".
describe('the park page’s boundary', () => {
  test('a park that arrived as a polygon says its boundary is mapped', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={mappedPark({
          category: 'national-park',
          Unit_Nm: 'Yosemite National Park',
          State_Nm: 'California',
        })}
      />,
    );

    expect(screen.getByText('Boundary')).toBeInTheDocument();
    expect(screen.getByText('Mapped')).toBeInTheDocument();
  });

  test('a park that arrived as a point claims nothing', () => {
    render(
      <ParkPoiPage
        variant="page"
        feature={park({
          category: 'national-park',
          Unit_Nm: 'Yosemite National Park',
          State_Nm: 'California',
          GIS_Acres: 761747,
        })}
      />,
    );

    // The area still prints — only the containment claim is withheld.
    expect(screen.getByText('761,747 acres')).toBeInTheDocument();
    expect(screen.queryByText('Boundary')).toBeNull();
  });
});
