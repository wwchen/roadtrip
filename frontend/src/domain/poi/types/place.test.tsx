import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { poiPageFor } from './registry';

const feature = (properties: Record<string, unknown>): FlatPoiFeature => ({
  type: 'Feature',
  id: 'pf-1',
  geometry: { type: 'Point', coordinates: [-93.6, 41.7] },
  properties,
});

const renderPoi = (properties: Record<string, unknown>) => {
  const Page = poiPageFor(properties);
  if (!Page) throw new Error('no page for this category');
  return render(<Page variant="page" feature={feature(properties)} />);
};

const gym = (extra: Record<string, unknown> = {}) => ({
  category: 'planet_fitness_location',
  name: 'Planet Fitness',
  // Promoted from the OSM tag bag by `flattenHydratedPoi`; every real gym has it.
  brand: 'Planet Fitness',
  street: '1205 SE 16th Ct',
  city: 'Ankeny',
  state: 'IA',
  ...extra,
});

// A gym is a shower on a long drive. Everything it knows is shown above the rule,
// so the page is short on purpose — these pin that it is short for the right reason
// rather than because a field silently failed to resolve.
describe('the gym page', () => {
  test('does not print the brand twice', () => {
    renderPoi(gym());
    // The title carries "Planet Fitness" already, because the ETL defaults the
    // record's name to it; the eyebrow says what kind of pin this is and stops.
    expect(screen.getByText('Gym')).toBeInTheDocument();
    expect(screen.queryByText('Gym · Planet Fitness')).not.toBeInTheDocument();
  });

  test('shows hours once, above the rule, rather than as a spec row', () => {
    renderPoi(gym({ opening_hours: 'Mo-Su 05:00-22:00' }));
    expect(screen.getAllByText('Mo-Su 05:00-22:00')).toHaveLength(1);
    expect(screen.queryByText('Hours')).not.toBeInTheDocument();
  });

  test('offers the number as a call action rather than a row to read', () => {
    renderPoi(gym({ phone: '+1 515-555-0113' }));
    expect(screen.getByRole('button', { name: /^Call/ })).toHaveAttribute(
      'href',
      'tel:+15155550113',
    );
    expect(screen.queryByText('Phone')).not.toBeInTheDocument();
  });

  test('renders no spec block at all, so no empty heading is left behind', () => {
    renderPoi(gym({ opening_hours: 'Mo-Su 05:00-22:00', phone: '+1 515-555-0113' }));
    expect(screen.queryByText('This location')).not.toBeInTheDocument();
  });

  test('offers no page button when the record carries no page', () => {
    // The page used to synthesise a chain-wide search here, which meant this file
    // had to know a chain URL. A button labelled "<chain> page" that lands on a
    // store locator answers a different question than it promises, so a record
    // with no page now simply has no button — as every other place type does.
    renderPoi(gym());
    expect(screen.queryByRole('button', { name: /page$/ })).not.toBeInTheDocument();
  });

  test('names the button from the record, not from a constant in the page', () => {
    // The chain arrives as data, so a differently-branded gym labels its own button
    // without the page or its registry row knowing the chain exists.
    renderPoi(gym({ brand: 'Anytime Fitness', website: 'https://www.anytimefitness.com/gyms/1234' }));
    expect(screen.getByRole('button', { name: 'Anytime Fitness page' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Planet Fitness page' })).not.toBeInTheDocument();
  });

  test('a record with no brand still gets a usable label', () => {
    renderPoi({ category: 'planet_fitness_location', website: 'https://example.test/gym' });
    expect(screen.getByRole('button', { name: 'Gym page' })).toBeInTheDocument();
  });

  test("links the record's own page", () => {
    renderPoi(gym({ website: 'https://www.planetfitness.com/gyms/ankeny-ia' }));
    expect(screen.getByRole('button', { name: 'Planet Fitness page' })).toHaveAttribute(
      'href',
      'https://www.planetfitness.com/gyms/ankeny-ia',
    );
  });
});

// The other place types still have their spec block: making `heading`/`fields`
// optional must not have made them optional in practice.
describe('the place types that do have a spec block', () => {
  test('a trailhead still shows one', () => {
    renderPoi({ category: 'trailhead', name: 'Mist Trail', trail_distance: '11 km' });
    expect(screen.getByText('The hike')).toBeInTheDocument();
    expect(screen.getByText('11 km')).toBeInTheDocument();
  });
});
