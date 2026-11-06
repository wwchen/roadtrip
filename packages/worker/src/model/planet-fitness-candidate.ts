import type { OpaquePayloadJson } from '../etl/envelope.js';

export interface PlanetFitnessLocationUpsertCandidate {
  locationId: string;
  name: string;
  latitude: number;
  longitude: number;
  address?: OpaquePayloadJson | null;
  region?: string | null;
  country?: string | null;
  phone?: string | null;
  infoUrl?: string | null;
  openingHours?: string | null;
  amenities?: OpaquePayloadJson | null;
  payload?: OpaquePayloadJson | null;
}
