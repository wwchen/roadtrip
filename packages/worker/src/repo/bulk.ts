import { encodeCatalogJson, sourcePayloadShape, type Db, type JsonObject, type Shape } from '@roadtrip/db';
import { sql, type SQL } from 'drizzle-orm';
import type { AnyPgColumn } from 'drizzle-orm/pg-core';
import { serializeOpaquePayload, type OpaquePayloadJson } from '../etl/envelope.js';

export const MAX_CATALOG_UPSERT_BATCH_SIZE = 1000;
export const BULK_CHUNK_SIZE = 500;

const PROVIDER_REF_KEY_SEPARATOR = '\u0000';
const ABSENT_OBJECT = serializeOpaquePayload({});
const ABSENT_ARRAY = serializeOpaquePayload([]);

export interface FlushCounts {
  upserted: number;
  skipped: number;
}

export type Executor = Pick<Db, 'execute' | 'insert' | 'select' | 'update'>;

export function requireCatalogBatchWithinLimit(label: string, size: number): void {
  if (size > MAX_CATALOG_UPSERT_BATCH_SIZE) {
    throw new Error(`${label} batch size ${size} exceeds max ${MAX_CATALOG_UPSERT_BATCH_SIZE}`);
  }
}

export function distinctBy<T>(items: readonly T[], keyOf: (item: T) => string): T[] {
  const firstByKey = new Map<string, T>();
  for (const item of items) {
    const key = keyOf(item);
    if (!firstByKey.has(key)) firstByKey.set(key, item);
  }
  return [...firstByKey.values()];
}

export function chunked<T>(items: readonly T[], size: number = BULK_CHUNK_SIZE): T[][] {
  const chunks: T[][] = [];
  for (let start = 0; start < items.length; start += size) chunks.push(items.slice(start, start + size));
  return chunks;
}

export function providerRefKey(provider: string, ref: string): string {
  return `${provider}${PROVIDER_REF_KEY_SEPARATOR}${ref}`;
}

export function jsonbParam(json: string): SQL {
  return sql`${json}::jsonb`;
}

export function encodedColumn<T>(shape: Shape<T>, value: T): SQL {
  return jsonbParam(JSON.stringify(encodeCatalogJson(shape, value)));
}

export function encodedNullableColumn<T>(shape: Shape<T>, value: T): SQL | null {
  const encoded = encodeCatalogJson(shape, value);
  return encoded === null ? null : jsonbParam(JSON.stringify(encoded));
}

export function opaqueObjectColumn(value: OpaquePayloadJson | null | undefined): SQL {
  return jsonbParam(value ?? ABSENT_OBJECT);
}

export function opaqueArrayColumn(value: OpaquePayloadJson | null | undefined): SQL {
  return jsonbParam(value ?? ABSENT_ARRAY);
}

export function serializeAdapterPayload(value: JsonObject): OpaquePayloadJson {
  return serializeOpaquePayload(encodeCatalogJson(sourcePayloadShape, value));
}

export function excluded<K extends string>(
  columns: Readonly<Record<K, AnyPgColumn>>,
  keys: readonly K[],
): Record<K, SQL> {
  return Object.fromEntries(keys.map((key) => [key, sql.raw(`excluded."${columns[key].name}"`)])) as Record<
    K,
    SQL
  >;
}
