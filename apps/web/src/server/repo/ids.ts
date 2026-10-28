import type { campgrounds, pois } from '@roadtrip/db';

type IdColumn = typeof pois.id | typeof campgrounds.id;

export function idString(column: IdColumn, raw: unknown): string {
  return String(column.mapFromDriverValue(String(raw)));
}
