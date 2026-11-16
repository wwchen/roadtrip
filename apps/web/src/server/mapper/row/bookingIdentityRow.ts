import { bookingAliasesShape } from '@roadtrip/db';
import type { BookingAlias } from '@roadtrip/domain';
import type { ColumnDecoder } from '../../repo/columnDecoder';

export interface BookingIdentityRow {
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
}

export function bookingIdentityOf(row: Record<string, unknown>, decoder: ColumnDecoder, prefix = ''): BookingIdentityRow {
  const aliasesColumn = `${prefix}booking_aliases`;
  return {
    bookingProvider: row[`${prefix}booking_provider`] as string | null,
    bookingProviderRef: row[`${prefix}booking_provider_ref`] as string | null,
    bookingAliases: decoder.decode(aliasesColumn, bookingAliasesShape, row[aliasesColumn]) ?? [],
  };
}
