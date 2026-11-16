import type { AddToCartCapabilityDto } from '../../contract';
import { RECGOV_PROVIDER, type AddToCartCapability } from '../../service/booking/model';

export function capabilityDto(capability: AddToCartCapability): AddToCartCapabilityDto {
  if (capability.state === 'unsupported') return { state: 'unsupported' };
  return { state: capability.state, provider: RECGOV_PROVIDER, provider_display: capability.providerDisplay };
}
