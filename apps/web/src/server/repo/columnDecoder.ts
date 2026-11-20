import { CatalogJsonError, decodeCatalogJson, type Shape } from '@roadtrip/db';

const DECODE_LOG = 'catalog column did not decode:';

export interface ColumnDecoder {
  readonly decodeErrors: string[];
  decode<T>(column: string, shape: Shape<T>, raw: unknown): T | null;
}

export function createColumnDecoder(row: string): ColumnDecoder {
  const decodeErrors: string[] = [];
  return {
    decodeErrors,
    decode<T>(column: string, shape: Shape<T>, raw: unknown): T | null {
      try {
        return decodeCatalogJson(shape, raw);
      } catch (error) {
        if (!(error instanceof CatalogJsonError)) throw error;
        console.error(DECODE_LOG, row, column, error.message);
        decodeErrors.push(column);
        return null;
      }
    },
  };
}
