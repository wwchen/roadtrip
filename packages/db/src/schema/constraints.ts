import { sql, type SQL } from 'drizzle-orm';

const textLiterals = (values: readonly string[]) =>
  values.map((value) => `'${value.replaceAll("'", "''")}'::text`).join(', ');

export const textIn = (column: string, values: readonly string[]): SQL =>
  sql.raw(`${column} = ANY (ARRAY[${textLiterals(values)}])`);

export const nullOrTextIn = (column: string, values: readonly string[]): SQL =>
  sql.raw(`${column} IS NULL OR ${column} = ANY (ARRAY[${textLiterals(values)}])`);

export const jsonbObject = (column: string): SQL =>
  sql.raw(`jsonb_typeof(${column}) = 'object'`);

export const jsonbArray = (column: string): SQL => sql.raw(`jsonb_typeof(${column}) = 'array'`);

export const nullOrJsonbObject = (column: string): SQL =>
  sql.raw(`${column} IS NULL OR jsonb_typeof(${column}) = 'object'`);

export const nullOrJsonbArray = (column: string): SQL =>
  sql.raw(`${column} IS NULL OR jsonb_typeof(${column}) = 'array'`);

export const nonBlank = (column: string): SQL => sql.raw(`length(btrim(${column})) > 0`);

export const atLeast = (column: string, minimum: number): SQL =>
  sql.raw(`${column} >= ${minimum}`);

export const nullOrAtLeast = (column: string, minimum: number): SQL =>
  sql.raw(`${column} IS NULL OR ${column} >= ${minimum}`);

export const nullOrBetween = (column: string, minimum: number, maximum: number): SQL =>
  sql.raw(`${column} IS NULL OR (${column} >= ${minimum} AND ${column} <= ${maximum})`);
