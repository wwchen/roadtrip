export function agencyDescriptor(agency) {
  if (agency === undefined || agency === null) return null;
  return agency.kind === 'constant' ? { kind: 'constant', value: agency.value } : { kind: 'derived', field: agency.field };
}

export function caseOf(name, entry, etl, inputs) {
  return {
    kind: 'etl',
    adapter: name,
    kotlin: entry.kotlin,
    ctor: entry.constructorProperties.map((property) => String(etl[property])),
    slug: etl.etlSlug,
    registry: { subcategory: null, agency: null, args: {}, inputs: Object.keys(inputs) },
    inputs,
  };
}

function plain(value) {
  if (value === null || value === undefined) return undefined;
  if (typeof value === 'bigint') return Number.isSafeInteger(Number(value)) ? Number(value) : value.toString();
  if (typeof value === 'object' && typeof value.serialize === 'function') return value.serialize();
  if (Array.isArray(value)) return value.map(plain);
  if (typeof value === 'object' && value.rawJSON === undefined) {
    return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, plain(entry)]));
  }
  return value;
}

function recordOf(record, opaqueFields) {
  const copy = { ...record };
  for (const key of opaqueFields) {
    if (typeof copy[key] === 'string') copy[key] = JSON.parse(copy[key]);
  }
  return plain(copy);
}

export function resultOf(result, opaqueFields) {
  if (!result.ok) return { ok: false, sourceId: result.sourceId ?? null, errors: result.errors };
  return { ok: true, record: recordOf(result.record, opaqueFields) };
}
