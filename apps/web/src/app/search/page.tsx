import Link from 'next/link';
import { BBOX_PARAMETER, BBOX_PROBLEM_MESSAGES, searchByBbox } from '@/server/bboxSearch';
import { DEFAULT_SEARCH_BBOX } from '@/server/boundary';
import type { CampgroundSearchDto } from '@/server/dto';

export const dynamic = 'force-dynamic';

const CAMPGROUND_PATH_PREFIX = '/campgrounds/';
const SINGULAR_COUNT = 1;

interface SearchPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

function counted(count: number, singular: string, plural: string): string {
  return `${count} ${count === SINGULAR_COUNT ? singular : plural}`;
}

function SearchResults({ result }: { result: CampgroundSearchDto }) {
  if (result.totalMatching === 0) return <p>No campgrounds matched this area.</p>;
  return (
    <>
      <p>
        {counted(result.totalMatching, 'matching campground', 'matching campgrounds')}
        {result.truncated ? `, showing the nearest ${result.campgrounds.length}` : ''}
      </p>
      <ul>
        {result.campgrounds.map((campground) => (
          <li key={campground.poiId}>
            <Link href={`${CAMPGROUND_PATH_PREFIX}${campground.poiId}`}>{campground.name}</Link>
            {campground.region === null ? '' : ` (${campground.region})`}
            {campground.siteSummary === null
              ? ''
              : `, ${counted(campground.siteSummary.siteTotal, 'site', 'sites')}`}
          </li>
        ))}
      </ul>
    </>
  );
}

export default async function SearchPage({ searchParams }: SearchPageProps) {
  const supplied = (await searchParams)[BBOX_PARAMETER];
  const outcome = await searchByBbox(supplied);
  const shown = typeof supplied === 'string' ? supplied : undefined;

  return (
    <main>
      <h1>Campground search</h1>
      <form method="get">
        <label>
          Bounding box (west,south,east,north){' '}
          <input name={BBOX_PARAMETER} defaultValue={shown ?? DEFAULT_SEARCH_BBOX} size={40} />
        </label>{' '}
        <button type="submit">Search</button>
      </form>
      {outcome.status === 'rejected' ? (
        <p role="alert">{BBOX_PROBLEM_MESSAGES[outcome.problem]}</p>
      ) : (
        <SearchResults result={outcome.result} />
      )}
    </main>
  );
}
