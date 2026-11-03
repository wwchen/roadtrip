import type { AgencyConfig, PoiRegistry } from '../registry/poi-registry.js';

const DERIVED_FROM_FIELD_KEY = 'derived_from_field';

export class TransformCtx {
  private constructor(
    private readonly subcategoryByEtlSlug: ReadonlyMap<string, string | undefined>,
    private readonly agencyByEtlSlug: ReadonlyMap<string, AgencyConfig | undefined>,
    private readonly argsByEtlSlug: ReadonlyMap<string, Readonly<Record<string, string>>>,
    readonly rawDir: string,
  ) {}

  static load(rawDir: string, registry: PoiRegistry): TransformCtx {
    const subcategories = new Map<string, string | undefined>();
    const agencies = new Map<string, AgencyConfig | undefined>();
    const args = new Map<string, Readonly<Record<string, string>>>();
    for (const row of registry.poiData) {
      const terminal = row.etls[row.etls.length - 1];
      if (terminal !== undefined) {
        subcategories.set(terminal.slug, row.subcategory);
        agencies.set(terminal.slug, row.agency);
      }
      for (const etl of row.etls) args.set(etl.slug, etl.args);
    }
    return new TransformCtx(subcategories, agencies, args, rawDir);
  }

  subcategoryFor(etlSlug: string): string | undefined {
    return this.subcategoryByEtlSlug.get(etlSlug);
  }

  agencyFor(etlSlug: string): AgencyConfig | undefined {
    return this.agencyByEtlSlug.get(etlSlug);
  }

  requiredConstantAgency(etlSlug: string): string {
    const agency = this.agencyFor(etlSlug);
    if (agency === undefined) throw new Error(`${etlSlug} is missing required poi_data.agency`);
    if (agency.kind === 'derived') {
      throw new Error(
        `${etlSlug} uses poi_data.agency.${DERIVED_FROM_FIELD_KEY}='${agency.field}', not a constant agency`,
      );
    }
    return agency.value;
  }

  argFor(etlSlug: string, key: string): string | undefined {
    const args = this.argsByEtlSlug.get(etlSlug);
    return args !== undefined && Object.hasOwn(args, key) ? args[key] : undefined;
  }
}
