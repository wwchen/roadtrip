import type { EtlEntry, GeometryPolicy, PoiRegistry } from '../registry/poi-registry.js';
import { AspiraCampgroundsEtl } from '../vendors/aspira/campgrounds.js';
import { BcParksCampgroundsEtl } from '../vendors/bcparks/campgrounds.js';
import { CampflareCampgroundsEtl } from '../vendors/campflare/campgrounds.js';
import { CampflareCampsitesEtl } from '../vendors/campflare/campsites.js';
import { PlanetFitnessEtl } from '../vendors/osmpf/planet-fitness.js';
import { RecGovCampgroundsEtl } from '../vendors/recgov/campgrounds.js';
import { RecGovCampsitesEtl } from '../vendors/recgov/campsites.js';
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

function requiredArg(entry: EtlEntry, key: string): string {
  const value = Object.hasOwn(entry.args, key) ? entry.args[key] : undefined;
  if (value === undefined) throw new Error(`Missing required ETL arg '${key}'`);
  return value;
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
  CampflareCampgroundsEtl: () => campgroundBinding(new CampflareCampgroundsEtl()),
  CampflareCampsitesEtl: () => campsiteBinding(new CampflareCampsitesEtl()),
  PlanetFitnessEtl: () => planetFitnessBinding(new PlanetFitnessEtl()),
  RecGovCampgroundsEtl: (entry) => campgroundBinding(new RecGovCampgroundsEtl(entry.slug)),
  RecGovCampsitesEtl: (entry) => campsiteBinding(new RecGovCampsitesEtl(entry.slug)),
  TeslaIndexEtl: () => teslaSuperchargerBinding(new TeslaIndexEtl()),
};

export function adapterFor(adapters: AdapterRegistry, name: string): AdapterFactory | undefined {
  return Object.hasOwn(adapters, name) ? adapters[name] : undefined;
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
