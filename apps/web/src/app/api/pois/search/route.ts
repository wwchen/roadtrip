import { searchPois } from '@/server/service/poiService';

export const dynamic = 'force-dynamic';

const DEFAULT_LIMIT = 10;
const MIN_LIMIT = 1;
const MAX_LIMIT = 25;
const VALUE_SEPARATOR = ',';
const INTEGER = /^-?\d+$/;

function boundedLimit(raw: string | null): number {
  const parsed = raw !== null && INTEGER.test(raw.trim()) ? Number(raw.trim()) : DEFAULT_LIMIT;
  return Math.min(Math.max(parsed, MIN_LIMIT), MAX_LIMIT);
}

export async function GET(request: Request): Promise<Response> {
  const params = new URL(request.url).searchParams;
  const categories = params
    .getAll('categories')
    .flatMap((value) => value.split(VALUE_SEPARATOR))
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  return Response.json(await searchPois((params.get('q') ?? '').trim(), boundedLimit(params.get('limit')), categories));
}
