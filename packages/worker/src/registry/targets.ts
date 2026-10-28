import type { EtlEntry, PoiRegistry } from './poi-registry.js';

export type ImportSection = 'poi_data' | 'campsite_data';

export interface ImportTarget {
  name: string;
  section: ImportSection;
}

interface Row {
  name: string;
  enabled: boolean;
  etls: EtlEntry[];
}

function requireEtls(row: Row): void {
  if (row.etls.length === 0) throw new Error(`registry row '${row.name}' declares no etls`);
}

function targetsFor(
  section: ImportSection,
  rows: readonly Row[],
  implemented: ReadonlySet<string>,
): ImportTarget[] {
  return rows
    .filter((row) => {
      if (!row.enabled) return false;
      requireEtls(row);
      return row.etls.every((etl) => implemented.has(etl.slug));
    })
    .map((row) => ({ name: row.name, section }));
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
