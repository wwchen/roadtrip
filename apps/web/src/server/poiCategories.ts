import { CAMPGROUND_POI_TYPE, POI_TYPES } from '@roadtrip/db';

export const SERVED_CATEGORIES: readonly string[] = POI_TYPES;
export const CAMPGROUND_MIN_ZOOM = 6;

const CATEGORY_ALIASES: Readonly<Record<string, string>> = {
  'planet-fitness': 'planet_fitness_location',
  supercharger: 'tesla_supercharger',
};

const MIN_ZOOM_BY_CATEGORY: Readonly<Record<string, number>> = { [CAMPGROUND_POI_TYPE]: CAMPGROUND_MIN_ZOOM };

export function canonicalCategories(categories: readonly string[]): string[] {
  return [...new Set(categories.map((category) => CATEGORY_ALIASES[category] ?? category))];
}

export function visibleAtZoom(category: string, zoom: number | null): boolean {
  const minZoom = MIN_ZOOM_BY_CATEGORY[category];
  return minZoom === undefined || zoom === null || zoom >= minZoom;
}
