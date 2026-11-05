import type { Db } from '@roadtrip/db';
import {
  BookingProvider,
  bookingIdentities,
  campsiteKindLabel,
  parseBookingProviderRef,
  servingAvailabilityProvider,
  shippedTenantRegistry,
  type AvailabilityProviderCapabilities,
  type BookingProviderRef,
  type CampsiteKind,
  type TenantRegistry,
} from '@roadtrip/domain';
import { config } from '../../config';
import type { CampsiteDto, PoiCampsitesResponseSchema } from '../../contract';
import { toContractId } from '../../contractIds';
import { serverDb } from '../../db';
import { findCampgroundsOfPoi, findCampsitesOfPoi, type CampsiteCampgroundRow, type CampsiteCatalogRow } from '../../repo/campsiteCatalogRepo';
import { aspiraBookingUrl } from '../booking/bookingUrls';

const CAMPSITE_RESPONSE_TYPE = 'campsite';
const RECGOV_CAMPSITE_URL = 'https://www.recreation.gov/camping/campsites';
const RECGOV_CAMPSITE_PATH = /(?:^|\/)campsites\/([^/?#]+)/;
const START_DATE = '{start_date}';
const END_DATE = '{end_date}';
const NIGHTS = '{nights}';
const ASPIRA_TEMPLATE_START = '2001-01-02';
const ASPIRA_TEMPLATE_END = '2001-01-03';
const ASPIRA_TEMPLATE_NIGHTS = 'nights=1';

export interface TemplateInputs {
  parentRef: BookingProviderRef;
  campsiteBookingRef: BookingProviderRef | null;
  reservationUrl: string | null;
  vendorSiteId: string;
  tenants: TenantRegistry;
}

function formEncode(value: string): string {
  return encodeURIComponent(value).replace(/[!'()~]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`).replace(/%20/g, '+');
}

function recGovTemplate(vendorId: string): string {
  return `${RECGOV_CAMPSITE_URL}/${formEncode(vendorId)}?startDate=${START_DATE}&endDate=${END_DATE}`;
}

function recGovCampsiteIdFromUrl(url: string | null): string | null {
  const trimmed = url?.trim() ?? '';
  if (trimmed.length === 0) return null;
  let path = trimmed;
  try {
    path = new URL(trimmed).pathname;
  } catch {
    path = trimmed;
  }
  return RECGOV_CAMPSITE_PATH.exec(path)?.[1] ?? null;
}

function aspiraTemplate(inputs: TemplateInputs): string | null {
  const parent = inputs.parentRef;
  if (parent.kind !== 'aspira') return null;
  const tenant = inputs.tenants.tenant(BookingProvider.ASPIRA, parent.tenant);
  if (tenant === null) return null;
  const own = inputs.campsiteBookingRef?.kind === 'aspira' && inputs.campsiteBookingRef.tenant === parent.tenant ? inputs.campsiteBookingRef : null;
  const ref = { ...parent, mapId: own?.mapId ?? parent.mapId, resourceLocationId: own?.resourceLocationId ?? parent.resourceLocationId };
  return aspiraBookingUrl(tenant.host, ref as typeof parent, ASPIRA_TEMPLATE_START, 1)
    .replaceAll(ASPIRA_TEMPLATE_START, START_DATE)
    .replaceAll(ASPIRA_TEMPLATE_END, END_DATE)
    .replace(ASPIRA_TEMPLATE_NIGHTS, `nights=${NIGHTS}`);
}

export function reservationTemplate(providerId: string, inputs: TemplateInputs): string | null {
  switch (providerId) {
    case BookingProvider.RECGOV:
      return recGovTemplate(inputs.vendorSiteId);
    case BookingProvider.CAMPFLARE: {
      const id = recGovCampsiteIdFromUrl(inputs.reservationUrl);
      return id === null ? null : recGovTemplate(id);
    }
    case BookingProvider.ASPIRA:
      return aspiraTemplate(inputs);
    default:
      return null;
  }
}

function vendorSiteId(campsite: CampsiteCatalogRow, providerId: string): string {
  if (campsite.bookingProvider === providerId && campsite.bookingProviderRef !== null) return campsite.bookingProviderRef;
  return campsite.bookingAliases.find((alias) => alias.provider === providerId)?.ref ?? campsite.dataProviderRef;
}

interface CampgroundContext {
  identities: BookingProviderRef[];
  serving: AvailabilityProviderCapabilities | null;
  parentRef: BookingProviderRef | null;
}

function forCampground(context: CampgroundContext, tenants: TenantRegistry): BookingProviderRef | null {
  return context.identities.find((ref) => tenants.sells(ref.provider)) ?? context.parentRef ?? context.identities[0] ?? null;
}

function bookingSiteRef(campsite: CampsiteCatalogRow, context: CampgroundContext, tenants: TenantRegistry): BookingProviderRef | null {
  if (context.serving === null) return forCampground(context, tenants);
  const sold = bookingIdentities(campsite.bookingProvider, campsite.bookingProviderRef, campsite.bookingAliases).find((ref) =>
    tenants.sells(ref.provider),
  );
  if (sold !== undefined) return context.identities.find((ref) => ref.provider === sold.provider) ?? sold;
  return forCampground(context, tenants);
}

function contextOf(campground: CampsiteCampgroundRow, enabled: readonly string[], tenants: TenantRegistry): CampgroundContext {
  const identities = bookingIdentities(campground.bookingProvider, campground.bookingProviderRef, campground.bookingAliases);
  const serving = servingAvailabilityProvider(identities, enabled, tenants);
  return { identities, serving, parentRef: serving === null ? null : (identities.find((ref) => ref.provider === serving.id) ?? null) };
}

function definedFields(fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(fields).filter(([, value]) => value !== null && value !== undefined));
}

function campsiteDto(campsite: CampsiteCatalogRow, bookingSystem: string | null): CampsiteDto {
  return definedFields({
    id: toContractId(campsite.id),
    campground_id: toContractId(campsite.campgroundId),
    name: campsite.name,
    kind: campsite.kind,
    kind_label: campsiteKindLabel(campsite.kind),
    kind_listed: campsite.kindListed,
    loop_name: campsite.loopName,
    description: campsite.description,
    min_people: campsite.minPeople,
    max_people: campsite.maxPeople,
    max_cars: campsite.maxCars,
    driveway_length: campsite.drivewayLength,
    max_rv_length: campsite.maxRvLength,
    max_trailer_length: campsite.maxTrailerLength,
    firepit: campsite.firepit,
    picnic_table: campsite.picnicTable,
    ada_accessible: campsite.adaAccessible,
    water_hookups: campsite.waterHookups,
    electric_hookups: campsite.electricHookups,
    sewer_hookups: campsite.sewerHookups,
    pull_through: campsite.pullThrough,
    equipment: campsite.equipment,
    attributes: campsite.attributes,
    photo_url: campsite.photoUrl,
    data_provider: campsite.dataProvider,
    data_provider_ref: campsite.dataProviderRef,
    booking_provider: campsite.bookingProvider,
    booking_system: bookingSystem,
  }) as unknown as CampsiteDto;
}

export async function campsiteList(
  db: Db,
  poiId: bigint,
  siteTypes: readonly CampsiteKind[],
  enabledAvailabilityProviders: readonly string[],
): Promise<PoiCampsitesResponseSchema | null> {
  const campgrounds = await findCampgroundsOfPoi(db, poiId);
  if (campgrounds.length === 0) return null;
  const tenants = shippedTenantRegistry();
  const contexts = new Map(campgrounds.map((campground) => [campground.campgroundId, contextOf(campground, enabledAvailabilityProviders, tenants)]));
  const rows = await findCampsitesOfPoi(db, poiId, siteTypes);
  const templates: Record<string, string> = {};
  const dtos = rows.map((campsite) => {
    const context = contexts.get(campsite.campgroundId)!;
    const site = bookingSiteRef(campsite, context, tenants);
    if (context.serving !== null && context.parentRef !== null) {
      const template = reservationTemplate(context.serving.id, {
        parentRef: context.parentRef,
        campsiteBookingRef:
          campsite.bookingProvider === null || campsite.bookingProviderRef === null
            ? null
            : parseBookingProviderRef(campsite.bookingProvider as BookingProvider, campsite.bookingProviderRef),
        reservationUrl: campsite.reservationUrl,
        vendorSiteId: vendorSiteId(campsite, context.serving.id),
        tenants,
      });
      if (template !== null) templates[String(campsite.id)] = template;
    }
    return campsiteDto(campsite, site === null ? null : tenants.displayName(site));
  });
  return { poi_id: toContractId(poiId), type: CAMPSITE_RESPONSE_TYPE, campsites: dtos, reservation_url_templates: templates };
}

export async function getCampsites(poiId: bigint, siteTypes: readonly CampsiteKind[]): Promise<PoiCampsitesResponseSchema | null> {
  return campsiteList(await serverDb(), poiId, siteTypes, config.enabledAvailabilityProviders);
}
