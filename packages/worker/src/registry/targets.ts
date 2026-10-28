import type { EtlEntry, PoiRegistry } from './poi-registry.js';

export type ImportSection = 'poi_data' | 'campsite_data';

export interface ImportTarget {
  name: string;
  section: ImportSection;
  entry: EtlEntry;
}

interface Row {
  name: string;
  enabled: boolean;
  etls: EtlEntry[];
}

function terminalEtl(row: Row): EtlEntry {
  const terminal = row.etls[row.etls.length - 1];
  if (terminal === undefined) throw new Error(`registry row '${row.name}' declares no etls`);
  return terminal;
}

function targetsFor(
  section: ImportSection,
  rows: readonly Row[],
  implemented: ReadonlySet<string>,
): ImportTarget[] {
  return rows
    .filter((row) => row.enabled && row.etls.every((etl) => implemented.has(etl.slug)))
    .map((row) => ({ name: row.name, section, entry: terminalEtl(row) }));
}

export function importTargets(
  registry: PoiRegistry,
  implemented: ReadonlySet<string>,
): ImportTarget[] {
  return [
    ...targetsFor('poi_data', registry.poiData, implemented),
    ...targetsFor('campsite_data', registry.campsiteData, implemented),
  ];
}
