import { apiError } from '@/server/contractIds';
import { geocode, GeocodeUnavailableError } from '@/server/service/routePlanService';
import { config } from '@/server/config';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const STATUS_UNAVAILABLE = 503;
const GEOCODING_UNAVAILABLE = 'geocoding_unavailable';
const BAD_QUERY = 'bad_query';
const TOKEN_NOT_SET = 'MAPBOX_TOKEN not set';
const MAX_QUERY_LENGTH = 200;
const DEFAULT_LIMIT = 5;
const MIN_LIMIT = 1;
const MAX_LIMIT = 10;
const AUTOCOMPLETE_OFF = '0';
const LNG_LAT = /^-?\d{1,3}(\.\d{1,8})?,-?\d{1,3}(\.\d{1,8})?$/;
const INTEGER = /^-?\d+$/;

export async function GET(request: Request): Promise<Response> {
  if (config.mapboxToken === undefined) return apiError(STATUS_UNAVAILABLE, GEOCODING_UNAVAILABLE, TOKEN_NOT_SET);
  const params = new URL(request.url).searchParams;
  const query = (params.get('q') ?? '').trim();
  if (query.length === 0 || query.length > MAX_QUERY_LENGTH) return apiError(STATUS_BAD_REQUEST, BAD_QUERY);
  const rawLimit = params.get('limit');
  const limit = Math.min(Math.max(rawLimit !== null && INTEGER.test(rawLimit) ? Number(rawLimit) : DEFAULT_LIMIT, MIN_LIMIT), MAX_LIMIT);
  const proximity = params.get('proximity') ?? undefined;
  try {
    const response = await geocode(query, {
      autocomplete: params.get('autocomplete') !== AUTOCOMPLETE_OFF,
      limit,
      ...(proximity !== undefined && LNG_LAT.test(proximity) ? { proximity } : {}),
    });
    return response === null ? apiError(STATUS_UNAVAILABLE, GEOCODING_UNAVAILABLE, TOKEN_NOT_SET) : Response.json(response);
  } catch (error) {
    if (error instanceof GeocodeUnavailableError) return apiError(STATUS_UNAVAILABLE, GEOCODING_UNAVAILABLE);
    throw error;
  }
}
