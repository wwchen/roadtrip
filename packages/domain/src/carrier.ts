import { memberList } from './members.js';

export const Carrier = {
  VERIZON: 'verizon',
  ATT: 'att',
  TMOBILE: 'tmobile',
  SPRINT: 'sprint',
  US_CELLULAR: 'uscell',
} as const;

export type Carrier = (typeof Carrier)[keyof typeof Carrier];

export const carriers: readonly Carrier[] = memberList(Carrier);

const CARRIER_LABELS: Readonly<Record<Carrier, string>> = {
  verizon: 'Verizon',
  att: 'AT&T',
  tmobile: 'T-Mobile',
  sprint: 'Sprint',
  uscell: 'US Cellular',
};

export function carrierLabel(carrier: Carrier): string {
  return CARRIER_LABELS[carrier];
}

export interface CarrierSignal {
  carrier: Carrier;
  average: number;
  count?: number;
}

const VENDOR_CARRIERS: ReadonlyMap<string, Carrier> = new Map([
  ['verizon', Carrier.VERIZON],
  ['at&t', Carrier.ATT],
  ['t-mobile', Carrier.TMOBILE],
  ['sprint', Carrier.SPRINT],
  ['us cellular', Carrier.US_CELLULAR],
]);

const KOTLIN_WHITESPACE_EDGE = /^[\p{Z}\t\n\v\f\r\x1C-\x1F]+|[\p{Z}\t\n\v\f\r\x1C-\x1F]+$/gu;

export function carrierFromVendorName(name: string): Carrier | null {
  return VENDOR_CARRIERS.get(name.replace(KOTLIN_WHITESPACE_EDGE, '').toLowerCase()) ?? null;
}
