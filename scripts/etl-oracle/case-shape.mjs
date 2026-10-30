import { instanceFingerprint } from './registry-seam.mjs';

export function agencyDescriptor(agency) {
  if (agency === undefined || agency === null) return null;
  return agency.kind === 'constant' ? { kind: 'constant', value: agency.value } : { kind: 'derived', field: agency.field };
}

export function caseOf(name, etl, inputs) {
  return {
    kind: 'etl',
    adapter: name,
    slug: etl.etlSlug,
    instance: instanceFingerprint(etl),
    registry: { subcategory: null, agency: null, args: {} },
    inputs,
  };
}

function plain(value) {
  if (value === null || value === undefined) return undefined;
  if (typeof value === 'bigint') return Number.isSafeInteger(Number(value)) ? Number(value) : value.toString();
  if (typeof value === 'object' && typeof value.serialize === 'function') {
    return typeof value.provider === 'string' ? { provider: value.provider, ref: value.serialize() } : value.serialize();
  }
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
