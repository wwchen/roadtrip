import { CatalogJsonError, decodeCatalogJson, type Shape } from '@roadtrip/db';

export interface ColumnDecoder {
  readonly decodeErrors: string[];
  decode<T>(column: string, shape: Shape<T>, raw: unknown): T | null;
}

export function createColumnDecoder(): ColumnDecoder {
  const decodeErrors: string[] = [];
  return {
    decodeErrors,
    decode<T>(column: string, shape: Shape<T>, raw: unknown): T | null {
      try {
        return decodeCatalogJson(shape, raw);
      } catch (error) {
        if (!(error instanceof CatalogJsonError)) throw error;
        decodeErrors.push(column);
        return null;
      }
    },
  };
}
