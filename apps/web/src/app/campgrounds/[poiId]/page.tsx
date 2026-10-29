import Link from 'next/link';
import { notFound } from 'next/navigation';
import { parsePoiId } from '@/server/boundary';
import { getCampgroundDetail } from '@/server/service/campgroundService';

export const dynamic = 'force-dynamic';

const SEARCH_PATH = '/search';
const LINKABLE_PROTOCOLS: readonly string[] = ['http:', 'https:'];

interface CampgroundPageProps {
  params: Promise<{ poiId: string }>;
}

function safeLink(raw: string): string | null {
  try {
    return LINKABLE_PROTOCOLS.includes(new URL(raw).protocol) ? raw : null;
  } catch {
    return null;
  }
}

function Field({ label, value }: { label: string; value: string | number | boolean | null }) {
  if (value === null) return null;
  return (
    <>
      <dt>{label}</dt>
      <dd>{String(value)}</dd>
    </>
  );
}

export default async function CampgroundPage({ params }: CampgroundPageProps) {
  const { poiId: rawPoiId } = await params;
  const poiId = parsePoiId(rawPoiId);
  if (poiId === null) notFound();

  const campground = await getCampgroundDetail(poiId);
  if (campground === null) notFound();

  const reservationLink =
    campground.reservationUrl === null ? null : safeLink(campground.reservationUrl);
  const present = (campground.amenities ?? []).filter((amenity) => amenity.present !== false);

  return (
    <main>
      <p>
        <Link href={SEARCH_PATH}>Back to search</Link>
      </p>
      <h1>{campground.name}</h1>
      {campground.parentName === null ? null : <p>{campground.parentName}</p>}
      {campground.shortDescription === null ? null : <p>{campground.shortDescription}</p>}
      {campground.longDescription === null ? null : <p>{campground.longDescription}</p>}
      <dl>
        <Field label="Kind" value={campground.kind} />
        <Field label="Status" value={campground.status} />
        <Field label="Status detail" value={campground.statusDescription} />
        <Field label="Region" value={campground.location?.region ?? null} />
        <Field label="Elevation" value={campground.location?.elevation ?? null} />
        <Field label="Directions" value={campground.location?.directions ?? null} />
        <Field label="Max RV length" value={campground.maxRvLength} />
        <Field label="Max trailer length" value={campground.maxTrailerLength} />
        <Field label="Big-rig friendly" value={campground.bigRigFriendly} />
        <Field label="Pull-through sites" value={campground.hasPullThroughSites} />
        <Field label="Price minimum" value={campground.price?.minimum ?? null} />
        <Field label="Price maximum" value={campground.price?.maximum ?? null} />
        <Field label="Currency" value={campground.price?.currency ?? null} />
        <Field label="Phone" value={campground.contact?.phone ?? null} />
        <Field label="Email" value={campground.contact?.email ?? null} />
        <Field label="Source" value={campground.dataProvider} />
      </dl>
      {present.length === 0 ? null : (
        <>
          <h2>Amenities</h2>
          <ul>
            {present.map((amenity) => (
              <li key={amenity.key}>
                {amenity.key}
                {amenity.detail === undefined ? '' : `: ${amenity.detail}`}
              </li>
            ))}
          </ul>
        </>
      )}
      {reservationLink === null ? null : (
        <p>
          <a href={reservationLink} rel="noopener noreferrer">
            Reserve
          </a>
        </p>
      )}
      {campground.decodeErrors.length === 0 ? null : (
        <p role="note">Some details could not be read and are not shown.</p>
      )}
    </main>
  );
}
