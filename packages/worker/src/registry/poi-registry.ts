import { readFileSync } from 'node:fs';
import { parse } from 'yaml';

export const DEFAULT_FUZZY_THRESHOLD = 0.5;

export type GeometryFormat =
  | 'uscampgrounds_csv'
  | 'bcparks_strapi'
  | 'arcgis_centroids'
  | 'geojson_points';

export interface Fetcher {
  executor?: string;
  filename?: string;
  args?: Record<string, string>;
  timeoutSec?: number;
  outputDirPrefix: string;
}

export interface DataSourceEntry {
  slug: string;
  name: string;
  dependsOn?: string[];
  fetcher: Fetcher;
}

export interface GeometrySourceSpec {
  input: string;
  format: GeometryFormat;
  state?: string;
  nameProperty?: string;
}

export interface MatchPolicy {
  fuzzyThreshold: number;
  parentFallback: boolean;
}

export interface GeometryPolicy {
  sources: GeometrySourceSpec[];
  match: MatchPolicy;
}

export interface EtlEntry {
  slug: string;
  adapter: string;
  inputs: string[];
  args: Record<string, string>;
  geometry?: GeometryPolicy;
}

export type AgencyConfig =
  | { kind: 'constant'; value: string }
  | { kind: 'derived'; field: string };

export interface PoiDataEntry {
  name: string;
  enabled: boolean;
  category: string;
  subcategory?: string;
  agency?: AgencyConfig;
  etls: EtlEntry[];
}

export interface CampsiteDataEntry {
  name: string;
  enabled: boolean;
  etls: EtlEntry[];
}

export interface TenantEntry {
  code?: string;
  host: string;
  displayName?: string;
}

export interface BookingProviderEntry {
  id: string;
  displayName: string;
  sells: boolean;
  tenants: TenantEntry[];
}

export interface PoiRegistry {
  dataSources: DataSourceEntry[];
  poiData: PoiDataEntry[];
  campsiteData: CampsiteDataEntry[];
  bookingProviders: BookingProviderEntry[];
  dataSource(slug: string): DataSourceEntry | undefined;
  poiDataByName(name: string): PoiDataEntry | undefined;
  campsiteDataByName(name: string): CampsiteDataEntry | undefined;
}

interface RawFetcher {
  executor?: string;
  filename?: string;
  args?: Record<string, string>;
  timeout_sec?: number;
  output_dir_prefix: string;
}

interface RawDataSource {
  slug: string;
  name: string;
  depends_on?: string[];
  fetcher: RawFetcher;
}

interface RawGeometrySource {
  input: string;
  format: GeometryFormat;
  state?: string;
  name_property?: string;
}

interface RawMatchPolicy {
  fuzzy_threshold?: number;
  parent_fallback?: boolean;
}

interface RawGeometryPolicy {
  sources: RawGeometrySource[];
  match?: RawMatchPolicy;
}

interface RawEtl {
  slug: string;
  adapter: string;
  inputs?: string[];
  args?: Record<string, string>;
  geometry?: RawGeometryPolicy;
}

interface RawDerivedAgency {
  derived_from_field: string;
}

interface RawPoiData {
  name: string;
  enabled?: boolean;
  category: string;
  subcategory?: string;
  agency?: string | RawDerivedAgency;
  etls: RawEtl[];
}

interface RawCampsiteData {
  name: string;
  enabled?: boolean;
  etls: RawEtl[];
}

interface RawTenant {
  code?: string;
  host: string;
  display_name?: string;
}

interface RawBookingProvider {
  id: string;
  display_name: string;
  sells: boolean;
  tenants?: RawTenant[];
}

interface RawRegistry {
  data_sources: RawDataSource[];
  poi_data: RawPoiData[];
  campsite_data?: RawCampsiteData[];
  booking_providers?: RawBookingProvider[];
}

function toFetcher(raw: RawFetcher): Fetcher {
  return {
    executor: raw.executor,
    filename: raw.filename,
    args: raw.args,
    timeoutSec: raw.timeout_sec,
    outputDirPrefix: raw.output_dir_prefix,
  };
}

function toDataSource(raw: RawDataSource): DataSourceEntry {
  return {
    slug: raw.slug,
    name: raw.name,
    dependsOn: raw.depends_on,
    fetcher: toFetcher(raw.fetcher),
  };
}

function toGeometry(raw: RawGeometryPolicy): GeometryPolicy {
  return {
    sources: raw.sources.map((source) => ({
      input: source.input,
      format: source.format,
      state: source.state,
      nameProperty: source.name_property,
    })),
    match: {
      fuzzyThreshold: raw.match?.fuzzy_threshold ?? DEFAULT_FUZZY_THRESHOLD,
      parentFallback: raw.match?.parent_fallback ?? false,
    },
  };
}

function toEtl(raw: RawEtl): EtlEntry {
  return {
    slug: raw.slug,
    adapter: raw.adapter,
    inputs: raw.inputs ?? [],
    args: raw.args ?? {},
    geometry: raw.geometry === undefined ? undefined : toGeometry(raw.geometry),
  };
}

function toAgency(raw: string | RawDerivedAgency | undefined): AgencyConfig | undefined {
  if (raw === undefined) return undefined;
  if (typeof raw === 'string') return { kind: 'constant', value: raw };
  return { kind: 'derived', field: raw.derived_from_field };
}

function toPoiData(raw: RawPoiData): PoiDataEntry {
  return {
    name: raw.name,
    enabled: raw.enabled ?? true,
    category: raw.category,
    subcategory: raw.subcategory,
    agency: toAgency(raw.agency),
    etls: raw.etls.map(toEtl),
  };
}

function toCampsiteData(raw: RawCampsiteData): CampsiteDataEntry {
  return {
    name: raw.name,
    enabled: raw.enabled ?? true,
    etls: raw.etls.map(toEtl),
  };
}

function toBookingProvider(raw: RawBookingProvider): BookingProviderEntry {
  return {
    id: raw.id,
    displayName: raw.display_name,
    sells: raw.sells,
    tenants: (raw.tenants ?? []).map((tenant) => ({
      code: tenant.code,
      host: tenant.host,
      displayName: tenant.display_name,
    })),
  };
}

export function loadPoiRegistry(yamlPath: string): PoiRegistry {
  const raw = parse(readFileSync(yamlPath, 'utf8')) as RawRegistry;
  const dataSources = raw.data_sources.map(toDataSource);
  const poiData = raw.poi_data.map(toPoiData);
  const campsiteData = (raw.campsite_data ?? []).map(toCampsiteData);
  const bookingProviders = (raw.booking_providers ?? []).map(toBookingProvider);
  return {
    dataSources,
    poiData,
    campsiteData,
    bookingProviders,
    dataSource: (slug) => dataSources.find((entry) => entry.slug === slug),
    poiDataByName: (name) => poiData.find((entry) => entry.name === name),
    campsiteDataByName: (name) => campsiteData.find((entry) => entry.name === name),
  };
}
