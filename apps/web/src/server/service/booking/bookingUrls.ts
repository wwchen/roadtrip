import type { AspiraBookingRef } from '@roadtrip/domain';
import { addDays } from './dateContext';

const RECGOV_CAMPGROUND_URL = 'https://www.recreation.gov/camping/campgrounds';
const RESERVECALIFORNIA_PARK_URL = 'https://reservecalifornia.com/park';
const CAMPFLARE_CAMPGROUND_URL = 'https://campflare.com/campground';

const ASPIRA_SEARCH_TAB_GROUP_ID = 0;
const ASPIRA_BOOKING_CATEGORY_ID = 0;
const ASPIRA_ANY_EQUIPMENT_ID = -32768;
const ASPIRA_ANY_SUB_EQUIPMENT_ID = -32768;
const ASPIRA_DEFAULT_CAPACITY_CATEGORY_ID = -32767;
const ASPIRA_DEFAULT_PEOPLE_COUNT = 1;
const ASPIRA_FLEXIBLE_SEARCH_RANGE_DAYS = 1;
const ASPIRA_VIEW = 'grid';
const ASPIRA_SEARCH_TIME_SUFFIX = 'T00:00:00.000';
const FORM_RESERVED = /[!'()~]/g;
const ENCODED_SPACE = /%20/g;
const MS_PER_DAY = 86_400_000;

function formEncode(value: string): string {
  return encodeURIComponent(value)
    .replace(FORM_RESERVED, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)
    .replace(ENCODED_SPACE, '+');
}

function queryString(params: readonly (readonly [string, string])[]): string {
  return params.map(([key, value]) => `${formEncode(key)}=${formEncode(value)}`).join('&');
}

function nightsBetween(startDate: string, endDate: string): number {
  return Math.round((Date.parse(endDate) - Date.parse(startDate)) / MS_PER_DAY);
}

export function aspiraBookingUrl(host: string, ref: AspiraBookingRef, startDate: string, nights: number): string {
  const endDate = addDays(startDate, nights);
  const params: [string, string][] = [
    ['transactionLocationId', String(ref.transactionLocationId)],
    ['mapId', String(ref.mapId)],
    ['searchTabGroupId', String(ASPIRA_SEARCH_TAB_GROUP_ID)],
    ['bookingCategoryId', String(ASPIRA_BOOKING_CATEGORY_ID)],
    ['startDate', startDate],
    ['endDate', endDate],
    ['nights', String(nightsBetween(startDate, endDate))],
    ['isReserving', 'true'],
    ['equipmentId', String(ASPIRA_ANY_EQUIPMENT_ID)],
    ['subEquipmentId', String(ASPIRA_ANY_SUB_EQUIPMENT_ID)],
    ['peopleCapacityCategoryCounts', JSON.stringify([[ASPIRA_DEFAULT_CAPACITY_CATEGORY_ID, null, ASPIRA_DEFAULT_PEOPLE_COUNT, null]])],
    ['searchTime', `${startDate}${ASPIRA_SEARCH_TIME_SUFFIX}`],
    ['flexibleSearch', JSON.stringify([false, false, startDate, ASPIRA_FLEXIBLE_SEARCH_RANGE_DAYS])],
    ['view', ASPIRA_VIEW],
  ];
  if (ref.resourceLocationId !== null) params.push(['resourceLocationId', String(ref.resourceLocationId)]);
  return `https://${host}/create-booking/results?${queryString(params)}`;
}

export function recGovCampgroundUrl(facilityId: string): string {
  return `${RECGOV_CAMPGROUND_URL}/${formEncode(facilityId)}`;
}

export function reserveCaliforniaParkUrl(placeId: bigint): string {
  return `${RESERVECALIFORNIA_PARK_URL}/${placeId}`;
}

export function campflareCampgroundUrl(campgroundId: string): string {
  return `${CAMPFLARE_CAMPGROUND_URL}/${campgroundId}`;
}
