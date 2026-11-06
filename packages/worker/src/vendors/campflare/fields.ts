import type {
  Address,
  CampgroundContact,
  CampgroundLink,
  CampgroundLocation,
  CampgroundManagement,
  CatalogPhoto,
} from '@roadtrip/domain';
import { isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import {
  CAMPFLARE_SOURCE_LINK_TITLE,
  doubleField,
  firstString,
  objectField,
  stringField,
} from './support.js';

const PHOTO_URL_KEYS = ['url', 'large_url', 'medium_url', 'small_url', 'original_url'] as const;
const LINK_URL_KEYS = ['url', 'href'] as const;
const LINK_TITLE_KEYS = ['title', 'label', 'name'] as const;
const AGENCY_KEYS = ['agency', 'agency_name'] as const;
const WEBSITE_KEYS = ['agency_website', 'website_url', 'website', 'url'] as const;
const PHONE_KEYS = ['phone', 'primary_phone'] as const;
const EMAIL_KEYS = ['email', 'primary_email'] as const;
const STREET_KEYS = ['street', 'street1', 'address_line'] as const;
const STATE_KEYS = ['state', 'state_code'] as const;
const POSTCODE_KEYS = ['postcode', 'postal_code', 'zipcode'] as const;
const COUNTRY_KEYS = ['country', 'country_code'] as const;

const LOCATION_FIELD = {
  region: 'region',
  country: 'country',
  elevation: 'elevation',
  directions: 'directions',
  address: 'address',
  city: 'city',
  full: 'full',
} as const;

function campflareAddress(address: JsonObject): Address | null {
  const parsed: Address = {
    street: firstString(address, STREET_KEYS) ?? undefined,
    city: stringField(address, LOCATION_FIELD.city) ?? undefined,
    state: firstString(address, STATE_KEYS) ?? undefined,
    postcode: firstString(address, POSTCODE_KEYS) ?? undefined,
    country: firstString(address, COUNTRY_KEYS) ?? undefined,
    full: stringField(address, LOCATION_FIELD.full) ?? undefined,
  };
  return Object.values(parsed).every((part) => part === undefined) ? null : parsed;
}

export function campflareLocation(location: JsonObject, latitude: number, longitude: number): CampgroundLocation {
  const address = objectField(location, LOCATION_FIELD.address);
  return {
    latitude,
    longitude,
    region: stringField(location, LOCATION_FIELD.region) ?? undefined,
    country: stringField(location, LOCATION_FIELD.country) ?? undefined,
    elevation: doubleField(location, LOCATION_FIELD.elevation) ?? undefined,
    directions: stringField(location, LOCATION_FIELD.directions) ?? undefined,
    address: (address === null ? null : campflareAddress(address)) ?? undefined,
  };
}

export function campflarePhotos(photos: readonly unknown[] | null): CatalogPhoto[] {
  const found: CatalogPhoto[] = [];
  for (const entry of photos ?? []) {
    const url = isJsonObject(entry) ? firstString(entry, PHOTO_URL_KEYS) : null;
    if (url !== null) found.push({ url });
  }
  return found;
}

export function campflareLinks(links: readonly unknown[] | null, sourceUrl: string): CampgroundLink[] {
  const parsed: CampgroundLink[] = [];
  for (const entry of links ?? []) {
    if (!isJsonObject(entry)) continue;
    const url = firstString(entry, LINK_URL_KEYS);
    if (url === null) continue;
    parsed.push({ url, title: firstString(entry, LINK_TITLE_KEYS) ?? undefined });
  }
  if (parsed.some((link) => link.url === sourceUrl)) return parsed;
  return [...parsed, { url: sourceUrl, title: CAMPFLARE_SOURCE_LINK_TITLE }];
}

export function campflareManagement(management: JsonObject | null): CampgroundManagement | null {
  const agency = management === null ? null : firstString(management, AGENCY_KEYS);
  if (management === null || agency === null) return null;
  return { agency, website: firstString(management, WEBSITE_KEYS) ?? undefined };
}

export function campflareContact(contact: JsonObject | null): CampgroundContact | null {
  const phone = contact === null ? null : firstString(contact, PHONE_KEYS);
  const email = contact === null ? null : firstString(contact, EMAIL_KEYS);
  if (phone === null && email === null) return null;
  return { phone: phone ?? undefined, email: email ?? undefined };
}
