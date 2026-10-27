import type { AmenityKey } from './amenity.js';
import { memberList } from './members.js';

export const BookingProvider = {
  RECGOV: 'recgov',
  CAMPFLARE: 'campflare',
  ASPIRA: 'aspira',
  RESERVEAMERICA: 'reserveamerica',
  RESERVECALIFORNIA: 'reservecalifornia',
} as const;

export type BookingProvider = (typeof BookingProvider)[keyof typeof BookingProvider];

export const bookingProviders: readonly BookingProvider[] = memberList(BookingProvider);

export interface Address {
  street?: string;
  city?: string;
  state?: string;
  postcode?: string;
  country?: string;
  full?: string;
}

export interface CampgroundLocation {
  latitude?: number;
  longitude?: number;
  region?: string;
  country?: string;
  elevation?: number;
  directions?: string;
  address?: Address;
}

export interface CampgroundAmenity {
  key: AmenityKey;
  present?: boolean;
  detail?: string;
}

export interface CampgroundPrice {
  minimum?: number;
  maximum?: number;
  currency?: string;
}

export interface CampgroundSchedule {
  check_in?: string;
  check_out?: string;
}

export interface CampgroundAlert {
  title?: string;
  body: string;
  ends_on?: string;
  source_url?: string;
}

export interface CampgroundRating {
  average: number;
  count: number;
}

export interface CampgroundMetadata {
  activities?: string[];
  rating?: CampgroundRating;
  last_updated?: string;
}

export interface BookingAlias {
  provider: BookingProvider;
  ref: string;
}

export interface CampgroundLink {
  url: string;
  title?: string;
}

export interface CampgroundContact {
  phone?: string;
  email?: string;
}

export interface CampgroundManagement {
  agency: string;
  website?: string;
}

export interface GeometryProvenance {
  match_kind: string;
  source: string;
  matched_name: string;
  score?: number;
}
