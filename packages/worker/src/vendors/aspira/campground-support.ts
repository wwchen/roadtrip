import { BookingProviderRef } from '@roadtrip/domain';
import type { Envelope } from '../../etl/envelope.js';
import { isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import type { RunLog } from '../../etl/run-log.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import type { MatchPolicy } from '../../registry/poi-registry.js';
import { bookableMapIdsByResourceLocationId, type AspiraBookingCtaRef } from './booking-cta-refs.js';
import { buildGeometryIndexOrFail, type GeometrySourceRef } from './geometry-index.js';
import type { GeometryPoint } from './geometry-point.js';
import { nonBookableResourceLocationIds } from './inventory-categories.js';
import type { AspiraLeaf } from './leaf.js';
import { AspiraLeafMatcher, type AspiraLeafMatchResult } from './leaf-matcher.js';

export const MAPS_ROLE = 'maps';
export const INVENTORY_ROLE = 'inventory';
export const DICTIONARIES_ROLE = 'dictionaries';
const HOST_ARG = 'host';
const BOOKING_URL_SCHEME = 'https://';
const BOOKING_URL_PATH = '/';
const NAME_KEY = 'name';
const TRANSACTION_LOCATION_ID_KEY = 'transactionLocationId';
const MAP_ID_KEY = 'mapId';
const RESOURCE_LOCATION_ID_KEY = 'resourceLocationId';
const PARENT_NAME_KEY = 'parent_name';

export interface AspiraLeafInputs {
  leaves: readonly AspiraLeaf[];
  geomSources: readonly GeometrySourceRef[];
  inventoryEnvelopes: readonly Envelope[];
  dictionaryPayload: JsonObject | null;
}

export interface AspiraRunSettings {
  host: string;
  bookingUrl: string;
  subcategory: string | undefined;
  agency: string;
}

export interface AspiraLeafMatches extends AspiraLeafMatchResult<GeometryPoint> {
  bookableMapIds: Map<bigint, Set<bigint>>;
}

export function objectPayloadOrNull(envelope: Envelope): JsonObject | null {
  return isJsonObject(envelope.payload) ? envelope.payload : null;
}

export function aspiraRunSettings(etlSlug: string, ctx: TransformCtx): AspiraRunSettings {
  const host = ctx.argFor(etlSlug, HOST_ARG);
  if (host === undefined) throw new Error(`${etlSlug}: missing args.${HOST_ARG}`);
  return {
    host,
    bookingUrl: `${BOOKING_URL_SCHEME}${host}${BOOKING_URL_PATH}`,
    subcategory: ctx.subcategoryFor(etlSlug),
    agency: ctx.requiredConstantAgency(etlSlug),
  };
}

export function matchAspiraLeaves(
  inputs: AspiraLeafInputs,
  policy: MatchPolicy,
  etlSlug: string,
  log: RunLog,
): AspiraLeafMatches {
  const nonBookable = nonBookableResourceLocationIds(inputs.inventoryEnvelopes, inputs.dictionaryPayload);
  const bookableMapIds = bookableMapIdsByResourceLocationId(inputs.inventoryEnvelopes, inputs.dictionaryPayload);
  const matcher = new AspiraLeafMatcher(buildGeometryIndexOrFail(inputs.geomSources, log, etlSlug), nonBookable, policy);
  return { ...matcher.matchBookable(inputs.leaves), bookableMapIds };
}

export function aspiraBookingProviderRef(tenant: string, leaf: AspiraLeaf, bookingCtaRef: AspiraBookingCtaRef): string {
  return BookingProviderRef.aspira(
    tenant,
    leaf.transactionLocationId,
    bookingCtaRef.mapId,
    bookingCtaRef.resourceLocationId,
  ).serialize();
}

export function leafSourcePayload(leaf: AspiraLeaf): Record<string, string | bigint> {
  return {
    [NAME_KEY]: leaf.name,
    [TRANSACTION_LOCATION_ID_KEY]: leaf.transactionLocationId,
    [MAP_ID_KEY]: leaf.mapId,
    ...(leaf.resourceLocationId === null ? {} : { [RESOURCE_LOCATION_ID_KEY]: leaf.resourceLocationId }),
    ...(leaf.parentName === null ? {} : { [PARENT_NAME_KEY]: leaf.parentName }),
  };
}
