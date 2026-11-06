import type {
  CampgroundAmenity,
  CampgroundContact,
  CampgroundLocation,
  CampgroundPrice,
} from '@roadtrip/domain';

export interface CampgroundDetailDto {
  poiId: string;
  campgroundId: string;
  dataProvider: string;
  dataProviderRef: string;
  name: string;
  parentName: string | null;
  kind: string | null;
  status: string | null;
  statusDescription: string | null;
  shortDescription: string | null;
  mediumDescription: string | null;
  longDescription: string | null;
  reservationUrl: string | null;
  bigRigFriendly: boolean | null;
  hasPullThroughSites: boolean | null;
  maxRvLength: number | null;
  maxTrailerLength: number | null;
  location: CampgroundLocation | null;
  amenities: CampgroundAmenity[] | null;
  price: CampgroundPrice | null;
  contact: CampgroundContact | null;
  decodeErrors: string[];
}

export interface CampgroundSiteSummaryDto {
  siteTotal: number;
  siteCounts: Record<string, number>;
  maxPeople: number | null;
}

export interface CampgroundSummaryDto {
  poiId: string;
  campgroundId: string;
  name: string;
  lng: number;
  lat: number;
  region: string | null;
  amenities: CampgroundAmenity[] | null;
  siteSummary: CampgroundSiteSummaryDto | null;
  decodeErrors: string[];
}

export interface CampgroundSearchDto {
  poiIds: string[];
  totalInBoundary: number;
  totalMatching: number;
  truncated: boolean;
  campgrounds: CampgroundSummaryDto[];
}
