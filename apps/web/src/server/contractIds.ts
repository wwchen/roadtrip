import type { ApiErrorSchema } from './contract';

export function toContractId(id: bigint): number {
  if (id > BigInt(Number.MAX_SAFE_INTEGER) || id < BigInt(Number.MIN_SAFE_INTEGER)) {
    throw new RangeError(`id ${id} does not fit a JSON number exactly`);
  }
  return Number(id);
}

export function apiError(status: number, code: string): Response {
  const body: ApiErrorSchema = { error: code };
  return Response.json(body, { status });
}
