import { readFileSync } from 'node:fs';

export interface Envelope {
  fetcher: string;
  fetcherVersion: string;
  fetchedAt: string;
  request: unknown;
  response: unknown;
  pollerRunId: number | null;
  payload: unknown;
  part?: string;
}

interface SourceAwareJson {
  parse(
    text: string,
    reviver: (this: unknown, key: string, value: unknown, context: { source?: string }) => unknown,
  ): unknown;
  rawJSON(text: string): object;
}

const sourceAwareJson = JSON as unknown as SourceAwareJson;
const INTEGER_LITERAL = /^-?\d+$/;

function reviveNumber(_key: string, value: unknown, context: { source?: string }): unknown {
  const source = context.source;
  if (typeof value !== 'number' || source === undefined) return value;
  const losesDigits = INTEGER_LITERAL.test(source) ? !Number.isSafeInteger(value) : !Number.isFinite(value);
  return losesDigits ? sourceAwareJson.rawJSON(source) : value;
}

function parseKeepingDigits(text: string): unknown {
  return sourceAwareJson.parse(text, reviveNumber);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function fail(file: string, reason: string): never {
  throw new Error(`${file}: invalid envelope: ${reason}`);
}

function requireString(file: string, record: Record<string, unknown>, field: string, label = field): string {
  const value = record[field];
  if (typeof value !== 'string') fail(file, `${label} must be a string`);
  return value;
}

function requireRecord(file: string, value: unknown, label: string): Record<string, unknown> {
  if (!isRecord(value)) fail(file, `${label} must be an object`);
  return value;
}

function requireStringMapIfPresent(file: string, record: Record<string, unknown>, label: string): void {
  const headers = record.headers;
  if (headers === undefined) return;
  const map = requireRecord(file, headers, `${label}.headers`);
  for (const [name, value] of Object.entries(map)) {
    if (typeof value !== 'string') fail(file, `${label}.headers.${name} must be a string`);
  }
}

function requirePollerRunId(file: string, value: unknown): number | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) fail(file, 'poller_run_id must be an integer or null');
  return value;
}

function requirePart(file: string, value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') fail(file, 'part must be a string or null');
  return value;
}

export function parseEnvelope(file: string): Envelope {
  let parsed: unknown;
  try {
    parsed = parseKeepingDigits(readFileSync(file, 'utf8'));
  } catch (cause) {
    throw new Error(`${file}: cannot read envelope: ${(cause as Error).message}`, { cause });
  }
  const wire = requireRecord(file, parsed, 'envelope');

  const request = requireRecord(file, wire.request, 'request');
  requireString(file, request, 'url', 'request.url');
  requireString(file, request, 'method', 'request.method');
  requireStringMapIfPresent(file, request, 'request');

  const response = requireRecord(file, wire.response, 'response');
  if (typeof response.status !== 'number' || !Number.isInteger(response.status)) {
    fail(file, 'response.status must be an integer');
  }
  requireStringMapIfPresent(file, response, 'response');

  if (!('payload' in wire)) fail(file, 'payload is required');

  const part = requirePart(file, wire.part);
  return {
    fetcher: requireString(file, wire, 'fetcher'),
    fetcherVersion: requireString(file, wire, 'fetcher_version'),
    fetchedAt: requireString(file, wire, 'fetched_at'),
    request,
    response,
    pollerRunId: requirePollerRunId(file, wire.poller_run_id),
    payload: wire.payload,
    ...(part === undefined ? {} : { part }),
  };
}
