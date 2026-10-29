const ASCII_WHITESPACE_RUN = /[ \t\n\v\f\r]+/g;
const KOTLIN_TRIM_EDGE = /^[\p{Z}\t\n\v\f\r\x1C-\x1F]+|[\p{Z}\t\n\v\f\r\x1C-\x1F]+$/gu;

export function collapseAsciiWhitespace(value: string): string {
  return value.replace(ASCII_WHITESPACE_RUN, ' ');
}

export function kotlinTrim(value: string): string {
  return value.replace(KOTLIN_TRIM_EDGE, '');
}

export function isKotlinBlank(value: string): boolean {
  return kotlinTrim(value) === '';
}

export function nonBlank(value: string | null | undefined): string | null {
  return value == null || isKotlinBlank(value) ? null : value;
}
