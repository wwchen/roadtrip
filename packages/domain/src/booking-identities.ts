import { bookingProviders, type BookingAlias, type BookingProvider } from './campground.js';
import { parseBookingProviderRef, type BookingProviderRef } from './provider.js';

function isBookingProvider(value: string | null): value is BookingProvider {
  return value !== null && (bookingProviders as readonly string[]).includes(value);
}

function refOf(provider: string | null, ref: string | null): BookingProviderRef | null {
  if (!isBookingProvider(provider) || ref === null || ref.trim().length === 0) return null;
  return parseBookingProviderRef(provider, ref);
}

export function bookingIdentities(
  bookingProvider: string | null,
  bookingProviderRef: string | null,
  aliases: readonly BookingAlias[],
): BookingProviderRef[] {
  return [refOf(bookingProvider, bookingProviderRef), ...aliases.map((alias) => refOf(alias.provider, alias.ref))].filter(
    (ref): ref is BookingProviderRef => ref !== null,
  );
}
