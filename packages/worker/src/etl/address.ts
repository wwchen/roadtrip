import { serializeOpaquePayload, type OpaquePayloadJson } from './envelope.js';

export interface Address {
  street: string | null;
  city: string | null;
  state: string | null;
  postcode: string | null;
  country: string | null;
}

export function addressJson(address: Address | null): OpaquePayloadJson | null {
  if (address === null) return null;
  const present = Object.entries(address).filter(([, value]) => value !== null);
  return serializeOpaquePayload(Object.fromEntries(present));
}
