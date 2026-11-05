import type { AvailabilityEgressRequestDto, AvailabilityEgressResponseDto } from '../contract';

export const EGRESS_PATH = '/api/internal/availability/fetch';
export const EGRESS_TIMEOUT_CODE = 'timeout';
export const EGRESS_UNREACHABLE_CODE = 'unreachable';
const NO_STATUS = 0;
const TIMEOUT_ERROR = 'TimeoutError';

export interface EgressEndpoint {
  url: URL;
  token: string;
}

export type EgressResult = { kind: 'cells'; body: AvailabilityEgressResponseDto } | { kind: 'error'; status: number; code: string | null };

export async function fetchEgress(endpoint: EgressEndpoint, request: AvailabilityEgressRequestDto, timeoutMs: number): Promise<EgressResult> {
  let response: Response;
  try {
    response = await fetch(new URL(EGRESS_PATH, endpoint.url), {
      method: 'POST',
      headers: { authorization: `Bearer ${endpoint.token}`, 'content-type': 'application/json' },
      body: JSON.stringify(request),
      signal: AbortSignal.timeout(timeoutMs),
      cache: 'no-store',
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === TIMEOUT_ERROR;
    return { kind: 'error', status: NO_STATUS, code: timedOut ? EGRESS_TIMEOUT_CODE : EGRESS_UNREACHABLE_CODE };
  }
  if (response.ok) return { kind: 'cells', body: (await response.json()) as AvailabilityEgressResponseDto };
  const body = (await response.json().catch(() => null)) as { error?: unknown } | null;
  return { kind: 'error', status: response.status, code: typeof body?.error === 'string' ? body.error : null };
}
