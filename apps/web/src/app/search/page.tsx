import Link from 'next/link';
import { DEFAULT_SEARCH_BBOX, bboxToPolygonGeoJson } from '@/server/boundary';
import type { CampgroundSearchDto } from '@/server/dto';
import { InvalidBoundaryError, searchCampgrounds } from '@/server/service/campgroundService';

export const dynamic = 'force-dynamic';

const BBOX_PARAMETER = 'bbox';
const CAMPGROUND_PATH_PREFIX = '/campgrounds/';
const INVALID_BBOX_MESSAGE =
  'The bounding box is not valid. Use west,south,east,north in degrees, for example';

interface SearchPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

async function searchOrNull(bbox: string): Promise<CampgroundSearchDto | null> {
  const boundary = bboxToPolygonGeoJson(bbox);
  if (boundary === null) return null;
  try {
    return await searchCampgrounds(boundary);
  } catch (error) {
    if (error instanceof InvalidBoundaryError) return null;
    throw error;
  }
}

function SearchResults({ result }: { result: CampgroundSearchDto }) {
  return (
    <>
      <p>
        {result.totalMatching} matching {result.totalMatching === 1 ? 'campground' : 'campgrounds'}
        {result.truncated ? `, showing the nearest ${result.campgrounds.length}` : ''}
      </p>
      <ul>
        {result.campgrounds.map((campground) => (
          <li key={campground.poiId}>
            <Link href={`${CAMPGROUND_PATH_PREFIX}${campground.poiId}`}>{campground.name}</Link>
            {campground.region === null ? '' : ` (${campground.region})`}
            {campground.siteSummary === null ? '' : `, ${campground.siteSummary.siteTotal} sites`}
          </li>
        ))}
      </ul>
    </>
  );
}

export default async function SearchPage({ searchParams }: SearchPageProps) {
  const supplied = (await searchParams)[BBOX_PARAMETER] ?? DEFAULT_SEARCH_BBOX;
  const bbox = typeof supplied === 'string' ? supplied : '';
  const result = await searchOrNull(bbox);

  return (
    <main>
      <h1>Campground search</h1>
      <form method="get">
        <label>
          Bounding box (west,south,east,north){' '}
          <input name={BBOX_PARAMETER} defaultValue={bbox} size={40} />
        </label>{' '}
        <button type="submit">Search</button>
      </form>
      {result === null ? (
        <p role="alert">
          {INVALID_BBOX_MESSAGE} <code>{DEFAULT_SEARCH_BBOX}</code>.
        </p>
      ) : (
        <SearchResults result={result} />
      )}
    </main>
  );
}
