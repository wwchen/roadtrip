import type { OpaquePayloadJson } from '../etl/envelope.js';

export const DEFAULT_TESLA_SITE_STATUS = 'open';

export interface TeslaSuperchargerUpsertCandidate {
  locationSlug: string;
  commonSiteName: string;
  latitude: number;
  longitude: number;
  locationGuid?: string | null;
  siteStatus?: string;
  accessType?: string | null;
  openToPublic?: boolean;
  openToNonTeslas?: boolean | null;
  trailerFriendly?: boolean | null;
  twentyFourSeven?: boolean | null;
  stallCount?: number | null;
  maxPowerKw?: number | null;
  address?: OpaquePayloadJson | null;
  region?: string | null;
  country?: string | null;
  timeZone?: string | null;
  amenities?: OpaquePayloadJson | null;
  hardwareCounts?: OpaquePayloadJson | null;
  pricebooks?: OpaquePayloadJson | null;
  availabilityProfile?: OpaquePayloadJson | null;
  infoUrl?: string | null;
  indexPayload?: OpaquePayloadJson | null;
  detailPayload?: OpaquePayloadJson | null;
}
