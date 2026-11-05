import { apiError } from '@/server/contractIds';
import { getPoiDetail } from '@/server/service/poiDetailService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const STATUS_NOT_FOUND = 404;
const BAD_ID = 'bad_id';
const NOT_FOUND = 'not_found';
const CACHE_CONTROL = 'public, max-age=300, stale-while-revalidate=3600';
const ID_PATTERN = /^-?\d+$/;
const MAX_ID = 2n ** 63n - 1n;
const MIN_ID = -(2n ** 63n);

function parseId(raw: string): bigint | null {
  if (!ID_PATTERN.test(raw)) return null;
  const id = BigInt(raw);
  return id > MAX_ID || id < MIN_ID ? null : id;
}

export async function GET(_request: Request, context: { params: Promise<{ id: string }> }): Promise<Response> {
  const id = parseId((await context.params).id);
  if (id === null) return apiError(STATUS_BAD_REQUEST, BAD_ID);
  const feature = await getPoiDetail(id);
  if (feature === null) return apiError(STATUS_NOT_FOUND, NOT_FOUND);
  return Response.json(feature, { headers: { 'Cache-Control': CACHE_CONTROL } });
}
