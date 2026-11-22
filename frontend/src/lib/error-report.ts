export interface ErrorFacts {
  readonly code: string | null;
  readonly detail: string | null;
  readonly status: number | null;
}

const NO_FACTS: ErrorFacts = { code: null, detail: null, status: null };

export const UNREPORTED_ERROR = 'error with no code or detail';

const FACT_SEPARATOR = ' · ';

function nonEmptyString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function serialized(value: unknown): string {
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

export function errorFactsOf(err: unknown): ErrorFacts {
  if (err == null) return NO_FACTS;
  if (typeof err === 'string') return { code: null, detail: nonEmptyString(err), status: null };
  if (typeof err !== 'object') return { code: null, detail: String(err), status: null };
  const carried = err as Record<string, unknown>;
  const status = finiteNumber(carried.status) ?? finiteNumber(carried.httpStatus);
  const code = nonEmptyString(carried.code) ?? nonEmptyString(carried.error);
  const detail = nonEmptyString(carried.detail);
  if (code !== null) return { code, detail, status };
  const name = nonEmptyString(carried.name);
  if (err instanceof Error || (name !== null && typeof carried.message === 'string')) {
    return detail !== null ? { code: null, detail, status } : { code: name, detail: nonEmptyString(carried.message), status };
  }
  return { code: null, detail: detail ?? nonEmptyString(carried.message) ?? serialized(err), status };
}

export function rawErrorText({ code, detail, status }: ErrorFacts): string {
  const head = code !== null && detail !== null ? `${code}: ${detail}` : (code ?? detail);
  return [head, status !== null ? `HTTP ${status}` : null].filter((part) => part !== null).join(FACT_SEPARATOR);
}

export function errorReport(friendly: string | null | undefined, facts: ErrorFacts): string {
  const raw = rawErrorText(facts.detail !== null && facts.detail === friendly ? { ...facts, detail: null } : facts);
  if (friendly) return raw ? `${friendly} (${raw})` : friendly;
  return raw || UNREPORTED_ERROR;
}

export function describeError(err: unknown, friendlyFor?: (code: string) => string | undefined): string {
  const facts = errorFactsOf(err);
  return errorReport(facts.code !== null ? friendlyFor?.(facts.code) : undefined, facts);
}
