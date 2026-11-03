import type {
  BookingAlias,
  BookingProvider,
  CampsiteAttribute,
  CampsiteKind,
  CatalogPhoto,
  DataProviderRef,
} from '@roadtrip/domain';
import type { OpaquePayloadJson } from '../etl/envelope.js';

export interface CampsiteUpsertCandidate {
  dataProviderRef: DataProviderRef;
  bookingProvider?: BookingProvider | null;
  bookingProviderRef?: string | null;
  bookingAliases?: BookingAlias[];
  parentDataProviderRef: DataProviderRef | null;
  name: string;
  kind?: CampsiteKind;
  loopName?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  reservationUrl?: string | null;
  equipment?: string[];
  kindListed?: string | null;
  schedule?: OpaquePayloadJson | null;
  price?: OpaquePayloadJson | null;
  firepit?: boolean | null;
  picnicTable?: boolean | null;
  adaAccessible?: boolean | null;
  waterHookups?: boolean | null;
  electricHookups?: boolean | null;
  sewerHookups?: boolean | null;
  maxPeople?: number | null;
  maxCars?: number | null;
  pullThrough?: boolean | null;
  drivewayLength?: number | null;
  maxRvLength?: number | null;
  maxTrailerLength?: number | null;
  photos?: CatalogPhoto[];
  attributes?: CampsiteAttribute[];
  description?: string | null;
  minPeople?: number | null;
  sourcePayload?: OpaquePayloadJson | null;
}
