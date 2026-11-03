import { DataProvider, dataProviders } from '@roadtrip/domain';
import type { EtlEntry, GeometryPolicy, PoiRegistry } from '../registry/poi-registry.js';
import { AspiraCampgroundsEtl } from '../vendors/aspira/campgrounds.js';
import { AspiraCampsitesEtl } from '../vendors/aspira/campsites.js';
import { BcParksCampgroundsEtl } from '../vendors/bcparks/campgrounds.js';
import { CampflareCampgroundsEtl } from '../vendors/campflare/campgrounds.js';
import { CampflareCampsitesEtl } from '../vendors/campflare/campsites.js';
import { PlanetFitnessEtl } from '../vendors/osmpf/planet-fitness.js';
import { RecGovCampgroundsEtl } from '../vendors/recgov/campgrounds.js';
import { RecGovCampsitesEtl } from '../vendors/recgov/campsites.js';
import { ReserveAmericaCampgroundsEtl } from '../vendors/reserveamerica/campgrounds.js';
import { ReserveAmericaSitesEtl } from '../vendors/reserveamerica/sites.js';
import { ReserveCaliforniaCampgroundsEtl } from '../vendors/reservecalifornia/campgrounds.js';
import { ReserveCaliforniaSitesEtl } from '../vendors/reservecalifornia/sites.js';
import { TeslaIndexEtl } from '../vendors/tesla/index-etl.js';
import type { AnyTerminalEtlBinding } from './terminal-binding.js';
import {
  campgroundBinding,
  campsiteBinding,
  planetFitnessBinding,
  teslaSuperchargerBinding,
} from './terminal-sinks.js';

export type AdapterFactory = (entry: EtlEntry) => AnyTerminalEtlBinding;
export type AdapterRegistry = Readonly<Record<string, AdapterFactory>>;

const TENANT_ARG = 'tenant';
const CONTRACT_ARG = 'contract';
const MAPS_INPUT_ARG = 'maps_input';
const INVENTORY_INPUT_ARG = 'inventory_input';
const DICTIONARIES_INPUT_ARG = 'dictionaries_input';
const PARENT_DATA_PROVIDER_ARG = 'parent_data_provider';

function optionalArg(entry: EtlEntry, key: string): string | undefined {
  return Object.hasOwn(entry.args, key) ? entry.args[key] : undefined;
}

function requiredArg(entry: EtlEntry, key: string): string {
  const value = optionalArg(entry, key);
  if (value === undefined) throw new Error(`Missing required ETL arg '${key}'`);
  return value;
}

function parentDataProvider(entry: EtlEntry): DataProvider {
  const id = optionalArg(entry, PARENT_DATA_PROVIDER_ARG);
  if (id === undefined) return DataProvider.ASPIRA;
  const found = dataProviders.find((provider) => provider === id);
  if (found === undefined) throw new Error(`Unknown data provider: ${id}`);
  return found;
}

function requiredGeometry(entry: EtlEntry): GeometryPolicy {
  if (entry.geometry === undefined) throw new Error(`${entry.slug}: geometry policy is required for ${entry.adapter}`);
  return entry.geometry;
}

export const adapterRegistry: Record<string, AdapterFactory> = {
  AspiraCampgroundsEtl: (entry) =>
    campgroundBinding(new AspiraCampgroundsEtl(entry.slug, requiredArg(entry, TENANT_ARG), requiredGeometry(entry))),
  BcParksCampgroundsEtl: (entry) =>
    campgroundBinding(new BcParksCampgroundsEtl(entry.slug, requiredArg(entry, TENANT_ARG), requiredGeometry(entry))),
  AspiraCampsitesEtl: (entry) =>
    campsiteBinding(
      new AspiraCampsitesEtl(
        entry.slug,
        requiredArg(entry, MAPS_INPUT_ARG),
        requiredArg(entry, INVENTORY_INPUT_ARG),
        optionalArg(entry, DICTIONARIES_INPUT_ARG) ?? null,
        requiredArg(entry, TENANT_ARG),
        parentDataProvider(entry),
      ),
    ),
  CampflareCampgroundsEtl: () => campgroundBinding(new CampflareCampgroundsEtl()),
  CampflareCampsitesEtl: () => campsiteBinding(new CampflareCampsitesEtl()),
  PlanetFitnessEtl: () => planetFitnessBinding(new PlanetFitnessEtl()),
  RecGovCampgroundsEtl: (entry) => campgroundBinding(new RecGovCampgroundsEtl(entry.slug)),
  RecGovCampsitesEtl: (entry) => campsiteBinding(new RecGovCampsitesEtl(entry.slug)),
  ReserveAmericaCampgroundsEtl: (entry) =>
    campgroundBinding(new ReserveAmericaCampgroundsEtl(entry.slug, requiredArg(entry, CONTRACT_ARG))),
  ReserveAmericaSitesEtl: (entry) =>
    campsiteBinding(new ReserveAmericaSitesEtl(entry.slug, requiredArg(entry, CONTRACT_ARG))),
  ReserveCaliforniaCampgroundsEtl: (entry) => campgroundBinding(new ReserveCaliforniaCampgroundsEtl(entry.slug)),
  ReserveCaliforniaSitesEtl: (entry) => campsiteBinding(new ReserveCaliforniaSitesEtl(entry.slug)),
  TeslaIndexEtl: () => teslaSuperchargerBinding(new TeslaIndexEtl()),
};

export function adapterFor(adapters: AdapterRegistry, name: string): AdapterFactory | undefined {
  return Object.hasOwn(adapters, name) ? adapters[name] : undefined;
}

const ADAPTER_SECTIONS = ['poi_data', 'campsite_data'] as const;
type AdapterSection = (typeof ADAPTER_SECTIONS)[number];

// ProductionTerminalEtlRegistry.kt keeps two maps, poiAdapters and
// campsiteAdapters, and resolves each section against its own: a poi_data row
// naming a campsite adapter is refused at registry init. adapterRegistry above
// is one map, so the section an adapter belongs to is declared here instead and
// checked below. Without it a poi_data row naming AspiraCampsitesEtl resolves,
// builds a campsiteBinding and writes campsite candidates for a POI target,
// where the Kotlin refuses to start.
export const ADAPTER_SECTION: Readonly<Record<string, AdapterSection>> = {
  AspiraCampgroundsEtl: 'poi_data',
  BcParksCampgroundsEtl: 'poi_data',
  CampflareCampgroundsEtl: 'poi_data',
  PlanetFitnessEtl: 'poi_data',
  RecGovCampgroundsEtl: 'poi_data',
  ReserveAmericaCampgroundsEtl: 'poi_data',
  ReserveCaliforniaCampgroundsEtl: 'poi_data',
  TeslaIndexEtl: 'poi_data',
  AspiraCampsitesEtl: 'campsite_data',
  CampflareCampsitesEtl: 'campsite_data',
  RecGovCampsitesEtl: 'campsite_data',
  ReserveAmericaSitesEtl: 'campsite_data',
  ReserveCaliforniaSitesEtl: 'campsite_data',
};

export function assertEveryEnabledAdapterResolves(registry: PoiRegistry, adapters: AdapterRegistry): void {
  const rowsBySection = { poi_data: registry.poiData, campsite_data: registry.campsiteData };
  const unknown: string[] = [];
  for (const section of ADAPTER_SECTIONS) {
    for (const row of rowsBySection[section]) {
      if (!row.enabled) continue;
      for (const etl of row.etls) {
        if (adapterFor(adapters, etl.adapter) === undefined) {
          unknown.push(`Unknown ${section} adapter: ${etl.adapter} (row='${row.name}', slug=${etl.slug})`);
          continue;
        }
        // Undefined for an adapter registered only by a test's own registry,
        // which is section-agnostic by construction.
        const declared = ADAPTER_SECTION[etl.adapter];
        if (declared !== undefined && declared !== section) {
          unknown.push(
            `Unknown ${section} adapter: ${etl.adapter} (row='${row.name}', slug=${etl.slug}): ` +
              `it is a ${declared} adapter`,
          );
        }
      }
    }
  }
  if (unknown.length === 0) return;
  throw new Error(
    `${unknown.join('; ')}. An enabled registry row whose adapter does not resolve would be skipped, ` +
      'and the import would report success over fewer sources than the registry declares.',
  );
}

export function implementedSlugs(registry: PoiRegistry, adapters: AdapterRegistry): Set<string> {
  const slugs = new Set<string>();
  for (const row of [...registry.poiData, ...registry.campsiteData]) {
    for (const etl of row.etls) {
      if (adapterFor(adapters, etl.adapter) !== undefined) slugs.add(etl.slug);
    }
  }
  return slugs;
}
