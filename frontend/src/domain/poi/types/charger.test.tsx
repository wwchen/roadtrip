import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { FlatPoiFeature } from '@/lib/poi';
import { poiPageFor } from './registry';

const feature = (properties: Record<string, unknown>): FlatPoiFeature => ({
  type: 'Feature',
  id: 'sc-1',
  geometry: { type: 'Point', coordinates: [-122.39, 40.59] },
  properties,
});

const renderCharger = (properties: Record<string, unknown>) => {
  const Page = poiPageFor(properties);
  if (!Page) throw new Error('no page for this category');
  return render(<Page variant="page" feature={feature(properties)} />);
};

// The backend serves the charger's canonical columns as named fields
// (stall_count, power_kilowatt, twenty_four_seven, …); the page reads those
// names directly. A rename on either side — the schema's @SerialName or a
// read here — compiles and passes every other gate, and the row it backs
// silently stops rendering.
describe('the charger page', () => {
  test('shows stall count and max power as one row', () => {
    renderCharger({
      category: 'tesla_supercharger',
      name: 'Cold Creek',
      stall_count: 12,
      power_kilowatt: 250,
    });
    expect(screen.getByText('12 · up to 250 kW')).toBeInTheDocument();
  });

  test('shows the 24/7 access chip', () => {
    renderCharger({
      category: 'tesla_supercharger',
      name: 'Cold Creek',
      twenty_four_seven: true,
    });
    expect(screen.getByText('24/7')).toBeInTheDocument();
  });

  // Tesla's vendor strings live under their own field: `amenities` is the typed
  // campground vocabulary and is always empty for a charger, so reading it here
  // rendered no tags at all.
  test('tags the vendor amenities from charger_amenities', () => {
    renderCharger({
      category: 'tesla_supercharger',
      name: 'Cold Creek',
      amenities: [],
      charger_amenities: ['AMENITIES_WIFI', 'AMENITIES_RESTROOMS'],
    });
    expect(screen.getByText('Wi-Fi')).toBeInTheDocument();
    expect(screen.getByText('Restrooms')).toBeInTheDocument();
  });
});
