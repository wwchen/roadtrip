// What every POI type component shares.
//
// A type component's whole job is to turn one provider's record into blocks. It
// never decides where a block goes — `PoiPageShell` owns the order — and it never
// renders page chrome, so the same component is the drawer and the routed page.
import type { ReactNode } from 'react';
import type { FlatPoiFeature } from '@/lib/poi';
import type { PoiPageVariant } from '../PoiPageShell';
import type { DetailGroup, DetailValue, StructuredDetails } from '../campground-detail';
import type { PoiNeighbour, PoiSpec } from '../model';

export interface PoiTypeProps {
  feature: FlatPoiFeature;
  variant?: PoiPageVariant;
  /** Panel only — the drawer's dismiss, which also fires after "add stop". */
  onClose?: () => void;
  /**
   * The availability grid, injected by whichever surface can render one.
   *
   * A render prop rather than an import: `features/availability` owns the week
   * controller and its queries, and a domain component may not reach into a
   * feature. The type decides whether the slot is used at all.
   */
  availability?: ReactNode;
  /**
   * The neighbours carousel's rows, injected by whichever surface can supply them.
   *
   * Injected data rather than a render prop, which is the difference from
   * `availability`: `PoiNeighbour` is already a domain shape, so the type keeps the
   * heading and the decision to render at all, and the injector only has to answer
   * "which places, how far, in what order".
   *
   * NOTHING SUPPLIES THIS YET, and that is deliberate — no surface passes it, so the
   * block is absent on every real record. What is missing is a proximity source:
   * - the API has no neighbours endpoint. `POST /api/pois` is bbox + zoom and ships
   *   `SlimPoiProperties` (category, subcategory, agency — no name), `GET
   *   /api/pois/search` ranks by name text and not by distance, and `POST
   *   /api/pois/on-route` is a route corridor, not a radius around one pin. The only
   *   proximity in the backend is Mapbox's `proximity=` geocoding bias, which biases
   *   address search and returns no POIs.
   * - so a client-side fallback would have to fetch a bbox of pins and then one
   *   `GET /api/pois/{id}` per pin just to learn the names, which is a request storm
   *   standing in for a query the server should answer.
   *
   * Filling it means a real "n closest to this POI" read, and the seam is here so
   * that lands as one caller passing this prop rather than as a rewrite of the block.
   */
  nearby?: PoiNeighbour[];
}

/** A type component. The registry maps a category onto one of these. */
export type PoiTypeComponent = (props: PoiTypeProps) => ReactNode;

/** One structured-detail group's rows as specs, or null when the group is empty. */
export function specsFrom(details: StructuredDetails, title: string): PoiSpec[] | null {
  const group = details.groups.find((candidate: DetailGroup) => candidate.title === title);
  if (!group || group.rows.length === 0) return null;
  return group.rows.map((row) => ({ label: row.label, value: <DetailValueView value={row.value} /> }));
}

/** Text, a link, or the connections chip row — whichever the extractor produced. */
export function DetailValueView({ value }: { value: DetailValue }) {
  if (value.kind === 'link') {
    return (
      <a href={value.href} target="_blank" rel="noreferrer">
        {value.label}
      </a>
    );
  }
  if (value.kind === 'chips') {
    return (
      <>
        {value.chips.map((chip) => (
          <span className="rt-poi-chip" key={chip.key}>
            <span>{chip.key}</span>
            <code>{chip.value}</code>
          </span>
        ))}
      </>
    );
  }
  return <>{value.text}</>;
}

/** Drop the empty entries, so a sparse record renders a short list rather than blanks. */
export const presentSpecs = (specs: (PoiSpec | null | false)[]): PoiSpec[] =>
  specs.filter((spec): spec is PoiSpec => Boolean(spec));
