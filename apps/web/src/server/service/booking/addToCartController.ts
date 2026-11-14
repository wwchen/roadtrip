import type { ActiveUser } from '../auth/model';
import { recgovTargetFor } from './bookingTarget';
import type { AddToCartCommand, AddToCartOutcome } from './model';
import type { BookingPorts } from './ports';

export async function addToCart(user: ActiveUser, command: AddToCartCommand, ports: BookingPorts): Promise<AddToCartOutcome> {
  const campsite = await ports.catalog.bookableCampsite(command.campsiteId);
  const target = campsite === null ? null : recgovTargetFor(campsite, campsite.campground, ports.tenants);
  if (campsite === null || target === null) return { kind: 'unsupported_target' };
  const providerDisplay = target.providerDisplay;
  const vault = await ports.vault.status(user.id);
  if (vault.kind !== 'stored') return { kind: 'credentials_required', providerDisplay };
  if (await ports.stored.takenIn(campsite, command.window)) return { kind: 'not_available', providerDisplay };
  const result = await ports.gateway.hold(user.id, target.vendorSiteId, command.window);
  switch (result.kind) {
    case 'held':
      return { kind: 'held', cartUrl: result.cartUrl, providerDisplay };
    case 'blocked':
      return { kind: 'refused', refusal: result.refusal, providerDisplay };
    case 'timed_out':
      return result.cartUrl === undefined
        ? { kind: 'refused', refusal: result.refusal, providerDisplay }
        : { kind: 'refused', refusal: result.refusal, providerDisplay, cartUrl: result.cartUrl };
    case 'unavailable':
      return { kind: 'unavailable', providerDisplay };
  }
}
