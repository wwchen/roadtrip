import type {
  BookingAlias,
  BookingProvider,
  CampgroundAlert,
  CampgroundAmenity,
  CampgroundContact,
  CampgroundLink,
  CampgroundLocation,
  CampgroundManagement,
  CampgroundMetadata,
  CampgroundPrice,
  CampgroundSchedule,
  CarrierSignal,
  CatalogPhoto,
  DataProviderRef,
  GeometryProvenance,
} from '@roadtrip/domain';
import type { OpaquePayloadJson } from '../etl/envelope.js';

export interface CampgroundUpsertCandidate {
  dataProviderRef: DataProviderRef;
  bookingProvider?: BookingProvider | null;
  bookingProviderRef?: string | null;
  bookingAliases?: BookingAlias[];
  name: string;
  parentName?: string | null;
  latitude: number;
  longitude: number;
  status?: string | null;
  statusDescription?: string | null;
  kind?: string | null;
  shortDescription?: string | null;
  mediumDescription?: string | null;
  longDescription?: string | null;
  location: CampgroundLocation;
  defaultCampsiteSchedule?: CampgroundSchedule | null;
  amenities?: CampgroundAmenity[];
  maxRvLength?: number | null;
  maxTrailerLength?: number | null;
  hasPullThroughSites?: boolean | null;
  bigRigFriendly?: boolean | null;
  reservationUrl?: string | null;
  links?: CampgroundLink[];
  photos?: CatalogPhoto[];
  alerts?: CampgroundAlert[];
  price?: CampgroundPrice | null;
  cellService?: CarrierSignal[];
  management?: CampgroundManagement | null;
  contact?: CampgroundContact | null;
  connections?: OpaquePayloadJson | null;
  metadata?: CampgroundMetadata | null;
  sourceUrl?: string | null;
  sourcePayload?: OpaquePayloadJson | null;
  geometryProvenance?: GeometryProvenance | null;
}
