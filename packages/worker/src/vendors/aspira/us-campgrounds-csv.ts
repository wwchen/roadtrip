import type { Envelope } from '../../etl/envelope.js';
import { contentOrNull } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText } from '../../etl/kotlin-number.js';
import { isKotlinBlank, kotlinTrim } from '../../etl/kotlin-text.js';
import type { GeometrySource } from './geometry-source.js';
import type { NamedPoint } from './named-point.js';

const LONGITUDE_COL = 0;
const LATITUDE_COL = 1;
const NAME_COL = 4;
const STATE_COL = 12;
const MIN_COLS = STATE_COL + 1;
const QUOTE = '"';
const COMMA = ',';
const EMPTY = '';
const KOTLIN_LINE_BREAK = /\r\n|\n|\r/;

export function csvSplit(line: string): string[] {
  const out: string[] = [];
  let field = EMPTY;
  let inQuotes = false;
  for (const character of line) {
    if (character === QUOTE) inQuotes = !inQuotes;
    else if (character === COMMA && !inQuotes) {
      out.push(field);
      field = EMPTY;
    } else field += character;
  }
  out.push(field);
  return out;
}

export class UsCampgroundsCsvSource implements GeometrySource {
  constructor(
    private readonly envelopes: readonly Envelope[],
    private readonly stateFilter: string | null = null,
  ) {}

  *points(): Iterable<NamedPoint> {
    const trimmed = this.stateFilter === null ? null : kotlinTrim(this.stateFilter).toUpperCase();
    const wantedState = trimmed === EMPTY ? null : trimmed;
    for (const envelope of this.envelopes) {
      const text = contentOrNull(envelope.payload);
      if (text === null) continue;
      for (const line of text.split(KOTLIN_LINE_BREAK)) {
        if (isKotlinBlank(line)) continue;
        const cols = csvSplit(line);
        if (cols.length < MIN_COLS) continue;
        if (wantedState !== null && kotlinTrim(cols[STATE_COL] as string).toUpperCase() !== wantedState) continue;
        const longitude = kotlinDoubleFromText(cols[LONGITUDE_COL] as string);
        if (longitude === null) continue;
        const latitude = kotlinDoubleFromText(cols[LATITUDE_COL] as string);
        if (latitude === null) continue;
        const name = kotlinTrim(cols[NAME_COL] as string);
        if (name === EMPTY) continue;
        yield { name, latitude, longitude };
      }
    }
  }
}
