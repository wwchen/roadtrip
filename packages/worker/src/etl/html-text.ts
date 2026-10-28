import { collapseAsciiWhitespace, kotlinTrim } from './kotlin-text.js';

const TAG = /<[^>]*>/g;
const TAG_REPLACEMENT = ' ';

export function stripTags(value: string): string {
  return kotlinTrim(collapseAsciiWhitespace(value.replace(TAG, TAG_REPLACEMENT)));
}
