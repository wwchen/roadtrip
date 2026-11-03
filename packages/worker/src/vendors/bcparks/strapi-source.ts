import type { Envelope } from '../../etl/envelope.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull, jsonArrayOf, jsonObjectOf } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText, kotlinStringToIntOrNull, kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';
import { kotlinStringToBooleanStrictOrNull, kotlinTrim, nonBlank } from '../../etl/kotlin-text.js';
import type { GeometrySource } from '../aspira/geometry-source.js';
import type { NamedPoint } from '../aspira/named-point.js';

const INT_MAX = 2147483647;
const DATA_KEY = 'data';
const PROTECTED_AREA_NAME_KEY = 'protectedAreaName';
const LATITUDE_KEY = 'latitude';
const LONGITUDE_KEY = 'longitude';
const ORCS_KEY = 'orcs';
const URL_KEY = 'url';
const DESCRIPTION_KEY = 'description';
const PARK_CONTACT_KEY = 'parkContact';
const PARK_PHOTOS_KEY = 'parkPhotos';
const IMAGE_URL_KEY = 'imageUrl';
const IS_ACTIVE_KEY = 'isActive';
const IS_FEATURED_KEY = 'isFeatured';
const SORT_ORDER_KEY = 'sortOrder';
const ACTIVE_BY_DEFAULT = true;
const FEATURED_BY_DEFAULT = false;

export interface BcParksStrapiRow {
  name: string;
  lat: number;
  lon: number;
  orcs: bigint | null;
  url: string | null;
  description: string | null;
  phone: string | null;
  photoUrl: string | null;
}

interface PhotoCandidate {
  url: string;
  featured: boolean;
  sortOrder: number;
}

function doubleText(row: JsonObject, key: string): number | null {
  const content = contentOrNull(fieldOf(row, key));
  return content === null ? null : kotlinDoubleFromText(content);
}

function trimmedText(row: JsonObject, key: string): string | null {
  const content = contentOrNull(fieldOf(row, key));
  return content === null ? null : nonBlank(kotlinTrim(content));
}

function photoUrlOf(photos: unknown): string | null {
  if (!Array.isArray(photos)) return null;
  const candidates: PhotoCandidate[] = [];
  for (const raw of photos) {
    if (!isJsonObject(raw)) continue;
    const url = nonBlank(contentOrNull(fieldOf(raw, IMAGE_URL_KEY)));
    if (url === null) continue;
    const activeText = contentOrNull(fieldOf(raw, IS_ACTIVE_KEY));
    const active = (activeText === null ? null : kotlinStringToBooleanStrictOrNull(activeText)) ?? ACTIVE_BY_DEFAULT;
    const featuredText = contentOrNull(fieldOf(raw, IS_FEATURED_KEY));
    const featured =
      (featuredText === null ? null : kotlinStringToBooleanStrictOrNull(featuredText)) ?? FEATURED_BY_DEFAULT;
    const sortText = contentOrNull(fieldOf(raw, SORT_ORDER_KEY));
    const sortOrder = (sortText === null ? null : kotlinStringToIntOrNull(sortText)) ?? INT_MAX;
    if (!active) continue;
    candidates.push({ url, featured, sortOrder });
  }
  const sorted = [...candidates].sort(
    (left, right) => Number(right.featured) - Number(left.featured) || left.sortOrder - right.sortOrder,
  );
  return sorted.length === 0 ? null : (sorted[0] as PhotoCandidate).url;
}

export class BcParksStrapiSource implements GeometrySource {
  private parsed: BcParksStrapiRow[] | null = null;

  constructor(private readonly envelopes: readonly Envelope[]) {}

  rows(): BcParksStrapiRow[] {
    if (this.parsed === null) this.parsed = this.parseRows();
    return this.parsed;
  }

  *points(): Iterable<NamedPoint> {
    for (const row of this.rows()) yield { name: row.name, latitude: row.lat, longitude: row.lon };
  }

  private parseRows(): BcParksStrapiRow[] {
    const rows: BcParksStrapiRow[] = [];
    for (const envelope of this.envelopes) {
      const data = fieldOf(jsonObjectOf(envelope.payload), DATA_KEY);
      if (data === undefined) continue;
      for (const raw of jsonArrayOf(data)) {
        const row = jsonObjectOf(raw);
        const name = nonBlank(contentOrNull(fieldOf(row, PROTECTED_AREA_NAME_KEY)));
        if (name === null) continue;
        const lat = doubleText(row, LATITUDE_KEY);
        if (lat === null) continue;
        const lon = doubleText(row, LONGITUDE_KEY);
        if (lon === null) continue;
        const orcsText = contentOrNull(fieldOf(row, ORCS_KEY));
        rows.push({
          name,
          lat,
          lon,
          orcs: orcsText === null ? null : kotlinStringToLongOrNull(orcsText),
          url: nonBlank(contentOrNull(fieldOf(row, URL_KEY))),
          description: trimmedText(row, DESCRIPTION_KEY),
          phone: trimmedText(row, PARK_CONTACT_KEY),
          photoUrl: photoUrlOf(fieldOf(row, PARK_PHOTOS_KEY)),
        });
      }
    }
    return rows;
  }
}
