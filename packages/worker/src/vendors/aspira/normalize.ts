import { kotlinTrim } from '../../etl/kotlin-text.js';

const NON_ALPHANUMERIC = /[^a-z0-9 ]/g;
const PARK_CRUFT =
  /\b(of\s+canada|national\s+park\s+reserve|national\s+park|national\s+historic\s+site|national\s+marine\s+conservation\s+area\s+reserve|national\s+marine\s+conservation\s+area|national\s+marine\s+park|park\s+reserve|state\s+park|state\s+recreation\s+area|state\s+forest|state\s+campground|provincial\s+park|recreation\s+area|park|reserve|campground|campsite|trailer\s+court|village|cabins?|centre|center)\b/g;
const WHITESPACE_RUN = /\s+/g;
const TRAILING_CONJUNCTION = /\s*\b(and|or)\b\s*$/g;
const SPACE = ' ';
const NOTHING = '';
const TOKEN_SEPARATOR = ' ';
const NO_OVERLAP = 0;

export function normalize(name: string): string {
  const lowered = name.toLowerCase().replace(NON_ALPHANUMERIC, SPACE).replace(PARK_CRUFT, SPACE);
  const collapsed = kotlinTrim(lowered.replace(WHITESPACE_RUN, SPACE));
  return kotlinTrim(collapsed.replace(TRAILING_CONJUNCTION, NOTHING));
}

export function tokensOf(key: string): ReadonlySet<string> {
  return new Set(key.split(TOKEN_SEPARATOR));
}

export function jaccard(a: ReadonlySet<string>, b: ReadonlySet<string>): number {
  if (a.size === 0 || b.size === 0) return NO_OVERLAP;
  let intersection = 0;
  for (const token of a) if (b.has(token)) intersection += 1;
  return intersection / (a.size + b.size - intersection);
}
