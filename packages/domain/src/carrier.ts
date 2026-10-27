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
