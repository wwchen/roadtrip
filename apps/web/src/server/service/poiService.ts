import { config } from '../config';
import type { PoiFeatureCollectionSchema, SlimPoiFeatureSchema } from '../contract';
import { toContractId } from '../contractIds';
import { serverDb } from '../db';
import { SERVED_CATEGORIES, visibleAtZoom } from '../poiCategories';
import type { Bbox } from '../poiRequest';
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
