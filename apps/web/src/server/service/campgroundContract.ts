import {
  AmenityKey,
  amenityLabel,
  amenityNegativeLabel,
  carrierLabel,
  type CampgroundAlert,
  type CampgroundAmenity,
  type CampgroundPrice,
  type CampgroundRating,
  type CampgroundSchedule,
  type CarrierSignal,
} from '@roadtrip/domain';
import type { PoiCategoryDetailSchema } from '../contract';

type AmenityDto = PoiCategoryDetailSchema['amenities'][number];
type CarrierSignalDto = PoiCategoryDetailSchema['cell_coverage'][number];
type AlertDto = PoiCategoryDetailSchema['alerts'][number];
type RatingDto = NonNullable<PoiCategoryDetailSchema['rating']>;
type PriceDto = NonNullable<PoiCategoryDetailSchema['price']>;
type ScheduleDto = NonNullable<PoiCategoryDetailSchema['schedule']>;

const MIN_SERVED_RATING_COUNT = 1;
const SCHEME_SEPARATOR = '://';
const HOST_TERMINATORS = /[/?#]/;
const WWW_PREFIX = 'www.';

const nonBlank = (value: string | undefined | null): string | undefined => {
  const trimmed = value?.trim();
  return trimmed === undefined || trimmed.length === 0 ? undefined : trimmed;
};

const capitalizeFirst = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

function amenityLabelFor(key: CampgroundAmenity['key'], present: boolean, detail: string | undefined): string {
  const negative = amenityNegativeLabel(key);
  if (key === AmenityKey.OTHER) return detail ?? amenityLabel(key);
  if (!present && negative !== undefined) return negative;
  if (key === AmenityKey.TOILETS && detail !== undefined) return `${capitalizeFirst(detail)} toilets`;
  if (detail !== undefined) return `${amenityLabel(key)}: ${detail}`;
  return amenityLabel(key);
}

export function amenityDtos(amenities: readonly CampgroundAmenity[]): AmenityDto[] {
  return amenities
    .filter((amenity) => (amenity.present ?? true) || amenityNegativeLabel(amenity.key) !== undefined)
    .map((amenity) => {
      const present = amenity.present ?? true;
      const detail = nonBlank(amenity.detail);
      return {
        key: amenity.key,
        label: amenityLabelFor(amenity.key, present, detail),
        present,
        ...(detail === undefined ? {} : { detail }),
      };
    });
}

export function carrierSignalDto(signal: CarrierSignal): CarrierSignalDto {
  return {
    carrier: signal.carrier,
    label: carrierLabel(signal.carrier),
    average: signal.average,
    ...(signal.count === undefined ? {} : { count: signal.count }),
  };
}

export function ratingDto(rating: CampgroundRating | undefined): RatingDto | undefined {
  if (rating === undefined || rating.count < MIN_SERVED_RATING_COUNT) return undefined;
  return { average: rating.average, count: rating.count };
}

export function priceDto(price: CampgroundPrice): PriceDto {
  return { ...price };
}

export function scheduleDto(schedule: CampgroundSchedule): ScheduleDto {
  return { ...schedule };
}

export function alertDto(alert: CampgroundAlert): AlertDto {
  return { ...alert };
}

export function bookingSiteOf(url: string): string | undefined {
  const schemeAt = url.indexOf(SCHEME_SEPARATOR);
  if (schemeAt <= 0) return undefined;
  const afterScheme = url.slice(schemeAt + SCHEME_SEPARATOR.length);
  const hostPort = afterScheme.split(HOST_TERMINATORS)[0] ?? '';
  const host = (hostPort.split(':')[0] ?? '').toLowerCase().trim();
  if (host.length === 0) return undefined;
  return host.startsWith(WWW_PREFIX) ? host.slice(WWW_PREFIX.length) : host;
}

export { nonBlank };
