import {
  BAD_POI_ID,
  STATUS_BAD_REQUEST,
  STATUS_NOT_FOUND,
  availabilityError,
  parseAvailabilityPoiId,
  parseAvailabilityQuery,
} from '@/server/availabilityRequest';
import {
  AvailabilityTargetError,
  AvailabilityUnavailableError,
  AvailabilityWindowRefusedError,
  getAvailabilityWeek,
} from '@/server/service/availability/availabilityMatrixService';

export const dynamic = 'force-dynamic';

const STATUS_SERVICE_UNAVAILABLE = 503;
const AVAILABILITY_UNAVAILABLE = 'availability_unavailable';
const PRIVATE_NO_STORE = 'private, no-store';

export async function GET(request: Request, context: { params: Promise<{ id: string }> }): Promise<Response> {
  const poiId = parseAvailabilityPoiId((await context.params).id);
  if (poiId === null) return availabilityError(STATUS_BAD_REQUEST, BAD_POI_ID);
  const query = parseAvailabilityQuery(new URL(request.url));
  if (query instanceof Response) return query;
  try {
    return Response.json(await getAvailabilityWeek(poiId, query.siteTypes, query.startDate, query.endDate), {
      headers: { 'cache-control': PRIVATE_NO_STORE },
    });
  } catch (error) {
    if (error instanceof AvailabilityWindowRefusedError) return Response.json(error.body, { status: STATUS_BAD_REQUEST });
    if (error instanceof AvailabilityUnavailableError) return availabilityError(STATUS_SERVICE_UNAVAILABLE, AVAILABILITY_UNAVAILABLE);
    if (error instanceof AvailabilityTargetError) return availabilityError(STATUS_NOT_FOUND, error.code);
    throw error;
  }
}
