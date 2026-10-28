import { sql, type SQL } from 'drizzle-orm';
import type { AnyPgColumn } from 'drizzle-orm/pg-core';

const ref = (column: AnyPgColumn) => `"${column.name}"`;

const textLiterals = (values: readonly string[]) =>
  values.map((value) => `'${value.replaceAll("'", "''")}'::text`).join(', ');

export const textIn = (column: AnyPgColumn, values: readonly string[]): SQL =>
  sql.raw(`${ref(column)} = ANY (ARRAY[${textLiterals(values)}])`);

export const nullOrTextIn = (column: AnyPgColumn, values: readonly string[]): SQL =>
  sql.raw(`${ref(column)} IS NULL OR ${ref(column)} = ANY (ARRAY[${textLiterals(values)}])`);

export const jsonbObject = (column: AnyPgColumn): SQL =>
  sql.raw(`jsonb_typeof(${ref(column)}) = 'object'`);

export const jsonbArray = (column: AnyPgColumn): SQL =>
  sql.raw(`jsonb_typeof(${ref(column)}) = 'array'`);

export const nullOrJsonbObject = (column: AnyPgColumn): SQL =>
  sql.raw(`${ref(column)} IS NULL OR jsonb_typeof(${ref(column)}) = 'object'`);

export const nullOrJsonbArray = (column: AnyPgColumn): SQL =>
  sql.raw(`${ref(column)} IS NULL OR jsonb_typeof(${ref(column)}) = 'array'`);

export const nonBlank = (column: AnyPgColumn): SQL =>
  sql.raw(`length(btrim(${ref(column)})) > 0`);

export const equalsText = (column: AnyPgColumn, value: string): SQL =>
  sql.raw(`${ref(column)} = ${textLiterals([value])}`);

export const atLeast = (column: AnyPgColumn, minimum: number): SQL =>
  sql.raw(`${ref(column)} >= ${minimum}`);

export const nullOrAtLeast = (column: AnyPgColumn, minimum: number): SQL =>
  sql.raw(`${ref(column)} IS NULL OR ${ref(column)} >= ${minimum}`);

export const nullOrBetween = (column: AnyPgColumn, minimum: number, maximum: number): SQL =>
  sql.raw(`${ref(column)} IS NULL OR (${ref(column)} >= ${minimum} AND ${ref(column)} <= ${maximum})`);

export const moreThan = (expression: string, floor: number): SQL =>
  sql.raw(`${expression} > ${floor}`);
