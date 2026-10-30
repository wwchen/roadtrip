const CONSTANT_AGENCY = 'constant';

export function etlEntryFor(registry, slug) {
  for (const row of [...registry.poiData, ...registry.campsiteData]) {
    for (const etl of row.etls) if (etl.slug === slug) return etl;
  }
  return undefined;
}

export function requireEtlEntry(registry, slug) {
  const entry = etlEntryFor(registry, slug);
  if (entry === undefined) throw new Error(`no etl with slug '${slug}' in the POI registry`);
  return entry;
}

function agencyOf(descriptor) {
  if (descriptor === null || descriptor === undefined) return undefined;
  return descriptor.kind === CONSTANT_AGENCY
    ? { kind: CONSTANT_AGENCY, value: descriptor.value }
    : { kind: 'derived', field: descriptor.field };
}

function ownsTerminal(row, slug) {
  const terminal = row.etls[row.etls.length - 1];
  return terminal !== undefined && terminal.slug === slug;
}

export function registryWithOverride(registry, slug, override) {
  const owner = registry.poiData.find((row) => ownsTerminal(row, slug));
  if (owner === undefined) {
    if (override.subcategory === null && override.agency === null) return registry;
    throw new Error(`no poi_data row has terminal etl '${slug}', so its recorded subcategory/agency cannot be applied`);
  }
  const poiData = registry.poiData.map((row) =>
    row === owner
      ? { ...row, subcategory: override.subcategory ?? undefined, agency: agencyOf(override.agency) }
      : row,
  );
  return { ...registry, poiData, poiDataByName: (name) => poiData.find((row) => row.name === name) };
}

export function seamProblems(cases, registry, adapters) {
  const problems = new Set();
  for (const current of cases) {
    const entry = etlEntryFor(registry, current.slug);
    if (entry === undefined) {
      problems.add(`${current.adapter}: no etl with slug '${current.slug}' in the POI registry`);
      continue;
    }
    const expectedAdapter = adapters[current.adapter]?.export;
    if (expectedAdapter !== entry.adapter) {
      problems.add(
        `${current.adapter}: adapters.json exports '${expectedAdapter}' but registry etl '${current.slug}' names adapter '${entry.adapter}'`,
      );
    }
    for (const [key, value] of Object.entries(current.registry.args)) {
      if (entry.args[key] !== value) {
        problems.add(
          `${current.adapter}: the unit test read args.${key}='${value}' for '${current.slug}', but the registry declares '${entry.args[key]}'`,
        );
      }
    }
  }
  return [...problems];
}
