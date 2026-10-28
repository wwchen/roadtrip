import type { EtlEntry, PoiRegistry } from '../registry/poi-registry.js';
import type { AnyTerminalEtlBinding } from './terminal-binding.js';

export type AdapterFactory = (entry: EtlEntry) => AnyTerminalEtlBinding;
export type AdapterRegistry = Readonly<Record<string, AdapterFactory>>;

export const adapterRegistry: Record<string, AdapterFactory> = {};

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
