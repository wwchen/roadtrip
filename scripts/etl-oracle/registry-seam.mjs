const CONSTANT_AGENCY = 'constant';
const MAX_FINGERPRINT_DEPTH = 12;

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
  if (!registry.poiData.some((row) => ownsTerminal(row, slug))) {
    if (override.subcategory === null && override.agency === null) return registry;
    throw new Error(`no poi_data row has terminal etl '${slug}', so its recorded subcategory/agency cannot be applied`);
  }
  const poiData = registry.poiData.map((row) =>
    ownsTerminal(row, slug)
      ? { ...row, subcategory: override.subcategory ?? undefined, agency: agencyOf(override.agency) }
      : row,
  );
  return { ...registry, poiData, poiDataByName: (name) => poiData.find((row) => row.name === name) };
}

function fingerprintValue(value, depth) {
  if (depth > MAX_FINGERPRINT_DEPTH) throw new Error('an etl instance nests deeper than the fingerprint walks');
  if (typeof value === 'function') return undefined;
  if (typeof value === 'bigint') return `bigint:${value}`;
  if (value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map((element) => fingerprintValue(element, depth + 1));
  const entries = Object.entries(value)
    .map(([key, entry]) => [key, fingerprintValue(entry, depth + 1)])
    .filter(([, entry]) => entry !== undefined)
    .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0));
  return Object.fromEntries(entries);
}

export function instanceFingerprint(etl) {
  return JSON.stringify({ class: etl.constructor.name, own: fingerprintValue({ ...etl }, 0) });
}

function constructedFingerprint(adapterRegistry, entry) {
  const factory = adapterRegistry[entry.adapter];
  if (factory === undefined) return { problem: `no adapter named '${entry.adapter}' in the TypeScript adapterRegistry` };
  try {
    return { fingerprint: instanceFingerprint(factory(entry).etl) };
  } catch (error) {
    return { problem: `adapterRegistry['${entry.adapter}'] refused registry etl '${entry.slug}': ${error.message}` };
  }
}

export function seamProblems(cases, registry, adapters, adapterRegistry) {
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
    const constructed = constructedFingerprint(adapterRegistry, entry);
    if (constructed.problem !== undefined) {
      problems.add(`${current.adapter}: ${constructed.problem}`);
    } else if (constructed.fingerprint !== current.instance) {
      problems.add(
        `${current.adapter}: the unit test ran ${current.instance}, but the registry row '${current.slug}' builds ` +
          `${constructed.fingerprint}; the recorded TypeScript results are not comparable with a replay of that row`,
      );
    }
  }
  return [...problems];
}
