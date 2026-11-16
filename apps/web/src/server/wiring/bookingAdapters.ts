import { appDb } from '../appDb';
import { bookingRelayGateway, unconfiguredBookingGateway } from '../client/bookingRelay';
import { config } from '../config';
import { credentialVault } from '../repo/credentialVault';
import type { BookingGateway, CredentialVault } from '../service/booking/ports';

export function liveGateway(): BookingGateway {
  const booking = config.booking;
  return booking === null ? unconfiguredBookingGateway : bookingRelayGateway(booking);
}

export function liveVault(): CredentialVault {
  return credentialVault(appDb(), config.bookingCipherKey);
}
