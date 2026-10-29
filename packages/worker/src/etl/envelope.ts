import { readFileSync } from 'node:fs';

export interface RequestMeta {
  url: string;
  method: string;
  headers: Record<string, string>;
}

export interface ResponseMeta {
  status: number;
  headers: Record<string, string>;
}

declare const opaquePayloadBrand: unique symbol;

export type OpaquePayloadJson = string & { readonly [opaquePayloadBrand]: 'jsonb-value' };

export type WideNumber = Readonly<{ rawJSON: string }>;

export interface Envelope {
  fetcher: string;
  fetcherVersion: string;
  fetchedAt: string;
  request: RequestMeta;
  response: ResponseMeta;
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
  isRawJSON(value: unknown): boolean;
}

const sourceAwareJson = JSON as unknown as SourceAwareJson;
const INTEGER_LITERAL = /^-?\d+$/;

function reviveNumber(_key: string, value: unknown, context: { source?: string }): unknown {
  const source = context.source;
  if (typeof value !== 'number' || source === undefined) return value;
  const losesInformation = INTEGER_LITERAL.test(source)
    ? !Number.isSafeInteger(value)
    : !Number.isFinite(value) || Number.isInteger(value);
  return losesInformation ? sourceAwareJson.rawJSON(source) : value;
}

export function isWideNumber(value: unknown): value is WideNumber {
  return sourceAwareJson.isRawJSON(value);
}

const OPAQUE_PAYLOAD_OPENERS = ['{', '['];

export function serializeOpaquePayload(value: unknown): OpaquePayloadJson {
  const serialized = JSON.stringify(value) as string | undefined;
  if (serialized === undefined || !OPAQUE_PAYLOAD_OPENERS.includes(serialized.charAt(0))) {
    throw new Error('opaque payload must serialise to a JSON object or array');
  }
  return serialized as OpaquePayloadJson;
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

function requireHeaders(file: string, record: Record<string, unknown>, label: string): Record<string, string> {
  if (record.headers === undefined) return {};
  const map = requireRecord(file, record.headers, `${label}.headers`);
  const headers: Record<string, string> = {};
  for (const [name, value] of Object.entries(map)) {
    if (typeof value !== 'string') fail(file, `${label}.headers.${name} must be a string`);
    headers[name] = value;
  }
  return headers;
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

  const requestWire = requireRecord(file, wire.request, 'request');
  const request: RequestMeta = {
    url: requireString(file, requestWire, 'url', 'request.url'),
    method: requireString(file, requestWire, 'method', 'request.method'),
    headers: requireHeaders(file, requestWire, 'request'),
  };

  const responseWire = requireRecord(file, wire.response, 'response');
  if (typeof responseWire.status !== 'number' || !Number.isInteger(responseWire.status)) {
    fail(file, 'response.status must be an integer');
  }
  const response: ResponseMeta = {
    status: responseWire.status,
    headers: requireHeaders(file, responseWire, 'response'),
  };

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
