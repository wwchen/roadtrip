import { config } from '../config';
import type { PoiFeatureCollectionSchema, PoiSearchResponseSchema, SlimPoiFeatureSchema } from '../contract';
import { toContractId } from '../contractIds';
import { serverDb } from '../db';
import { SERVED_CATEGORIES, canonicalCategories, visibleAtZoom } from '../poiCategories';
import type { Bbox } from '../poiRequest';
import { searchPoisByName } from '../repo/poiSearchRepo';
import { fetchPois, type PoiRow } from '../repo/poiServingRepo';

export { CAMPGROUND_MIN_ZOOM } from '../poiCategories';
export const POI_LIMIT = 2000;

const FEATURE_COLLECTION = 'FeatureCollection';
const FEATURE = 'Feature';
const POINT = 'Point';

function slimFeature(row: PoiRow): SlimPoiFeatureSchema {
  return {
    type: FEATURE,
    id: toContractId(row.id),
    geometry: { type: POINT, coordinates: [row.lng, row.lat] },
    properties: {
      category: row.category,
      ...(row.subcategory === null ? {} : { subcategory: row.subcategory }),
      ...(row.agency === null ? {} : { agency: row.agency }),
    },
  };
}

export async function viewportPois(bbox: Bbox, zoom: number | null, categories: string[] | null): Promise<PoiFeatureCollectionSchema> {
  const visible = (categories ?? [...SERVED_CATEGORIES]).filter((category) => visibleAtZoom(category, zoom));
  if (visible.length === 0) return { type: FEATURE_COLLECTION, truncated: false, features: [] };
  const { rows, truncated } = await fetchPois(await serverDb(), {
    bbox,
    categories: visible,
    limit: POI_LIMIT,
    providers: config.enabledDataProviders,
  });
  return { type: FEATURE_COLLECTION, truncated, features: rows.map(slimFeature) };
}

const MIN_SEARCH_QUERY_LENGTH = 2;

export async function searchPois(query: string, limit: number, categories: readonly string[]): Promise<PoiSearchResponseSchema> {
  if (query.length < MIN_SEARCH_QUERY_LENGTH) return { results: [] };
  const hits = await searchPoisByName(await serverDb(), query, canonicalCategories(categories), limit, config.enabledDataProviders);
  return {
    results: hits.map((hit) => ({
      id: toContractId(hit.id),
      name: hit.name,
      category: hit.category,
      ...(hit.region === null ? {} : { region: hit.region }),
      lng: hit.lng,
      lat: hit.lat,
    })),
  };
}
