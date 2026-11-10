import type { EgressAccessCredentials } from '../config';
import type { AvailabilityEgressRequestDto, AvailabilityEgressResponseDto, AvailabilityErrorDto } from '../contract';

export const EGRESS_PATH = '/api/internal/availability/fetch';
export const EGRESS_TIMEOUT_CODE = 'timeout';
export const EGRESS_UNREACHABLE_CODE = 'unreachable';
export const EGRESS_REDIRECTED_CODE = 'egress_redirected';
export const EGRESS_NOT_JSON_CODE = 'egress_not_json';
const NO_STATUS = 0;
const FIRST_REDIRECT_STATUS = 300;
const FIRST_CLIENT_ERROR_STATUS = 400;
const TIMEOUT_ERROR = 'TimeoutError';
const ACCESS_CLIENT_ID_HEADER = 'cf-access-client-id';
const ACCESS_CLIENT_SECRET_HEADER = 'cf-access-client-secret';

export interface EgressEndpoint {
  url: URL;
  access: EgressAccessCredentials;
}

function headersFor(endpoint: EgressEndpoint): Record<string, string> {
  return {
    'content-type': 'application/json',
    [ACCESS_CLIENT_ID_HEADER]: endpoint.access.clientId,
    [ACCESS_CLIENT_SECRET_HEADER]: endpoint.access.clientSecret,
  };
}

export type EgressResult =
  | { kind: 'cells'; body: AvailabilityEgressResponseDto }
  | { kind: 'error'; status: number; code: string | null; body: AvailabilityErrorDto | null };

export async function fetchEgress(endpoint: EgressEndpoint, request: AvailabilityEgressRequestDto, timeoutMs: number): Promise<EgressResult> {
  let response: Response;
  try {
    response = await fetch(new URL(EGRESS_PATH, endpoint.url), {
      method: 'POST',
      headers: headersFor(endpoint),
      body: JSON.stringify(request),
      signal: AbortSignal.timeout(timeoutMs),
      cache: 'no-store',
      redirect: 'manual',
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === TIMEOUT_ERROR;
    return { kind: 'error', status: NO_STATUS, code: timedOut ? EGRESS_TIMEOUT_CODE : EGRESS_UNREACHABLE_CODE, body: null };
  }
  if (response.status >= FIRST_REDIRECT_STATUS && response.status < FIRST_CLIENT_ERROR_STATUS) {
    return { kind: 'error', status: response.status, code: EGRESS_REDIRECTED_CODE, body: null };
  }
  if (response.ok) {
    const body = (await response.json().catch(() => null)) as AvailabilityEgressResponseDto | null;
    return body === null ? { kind: 'error', status: response.status, code: EGRESS_NOT_JSON_CODE, body: null } : { kind: 'cells', body };
  }
  const body = (await response.json().catch(() => null)) as AvailabilityErrorDto | null;
  const code = typeof body?.error === 'string' ? body.error : null;
  return { kind: 'error', status: response.status, code, body: code === null ? null : body };
}
