import {
  BAD_REQUEST,
  STATUS_BAD_REQUEST,
  STATUS_NOT_FOUND,
  parseAvailabilityPoiId,
  parseSiteTypes,
  unknownSiteTypeDetail,
} from '@/server/availabilityRequest';
import { apiError } from '@/server/contractIds';
import { getCampsites } from '@/server/service/availability/campsiteListService';

export const dynamic = 'force-dynamic';

const BAD_ID = 'bad_id';
const NOT_FOUND = 'not_found';

export async function GET(request: Request, context: { params: Promise<{ id: string }> }): Promise<Response> {
  const poiId = parseAvailabilityPoiId((await context.params).id);
  if (poiId === null) return apiError(STATUS_BAD_REQUEST, BAD_ID);
  const siteTypes = parseSiteTypes(new URL(request.url));
  if (!Array.isArray(siteTypes)) return apiError(STATUS_BAD_REQUEST, BAD_REQUEST, unknownSiteTypeDetail(siteTypes.unknown));
  const list = await getCampsites(poiId, siteTypes);
  return list === null ? apiError(STATUS_NOT_FOUND, NOT_FOUND) : Response.json(list);
}
