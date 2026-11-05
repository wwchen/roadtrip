import {
  BAD_POI_ID,
  STATUS_BAD_REQUEST,
  STATUS_NOT_FOUND,
  availabilityError,
  parseAvailabilityPoiId,
  parseAvailabilityQuery,
} from '@/server/availabilityRequest';
import { AvailabilityTargetError, getAvailabilityWeek } from '@/server/service/availability/availabilityMatrixService';
import { BadDateWindowError } from '@/server/service/availability/availabilityWindow';

export const dynamic = 'force-dynamic';

export async function GET(request: Request, context: { params: Promise<{ id: string }> }): Promise<Response> {
  const poiId = parseAvailabilityPoiId((await context.params).id);
  if (poiId === null) return availabilityError(STATUS_BAD_REQUEST, BAD_POI_ID);
  const query = parseAvailabilityQuery(new URL(request.url));
  if (query instanceof Response) return query;
  try {
    return Response.json(await getAvailabilityWeek(poiId, query.siteTypes, query.startDate, query.endDate));
  } catch (error) {
    if (error instanceof BadDateWindowError) return availabilityError(STATUS_BAD_REQUEST, error.code, error.extra);
    if (error instanceof AvailabilityTargetError) return availabilityError(STATUS_NOT_FOUND, error.code);
    throw error;
  }
}
