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

const CASE_FOLDS_TO_ASCII = new Map<number, string>([
  [0x130, 'i'],
  [0x131, 'i'],
  [0x17f, 's'],
]);

function foldedToAsciiLowerCase(codeUnit: number): string {
  return CASE_FOLDS_TO_ASCII.get(codeUnit) ?? String.fromCharCode(codeUnit).toLowerCase();
}

export function kotlinEqualsIgnoreCase(value: string | null | undefined, asciiTarget: string): boolean {
  if (value == null || value.length !== asciiTarget.length) return false;
  for (let index = 0; index < value.length; index += 1) {
    if (foldedToAsciiLowerCase(value.charCodeAt(index)) !== asciiTarget.charAt(index).toLowerCase()) return false;
  }
  return true;
}

const TRUE_TEXT = 'true';
const FALSE_TEXT = 'false';

export function kotlinStringToBooleanStrictOrNull(value: string): boolean | null {
  if (value === TRUE_TEXT) return true;
  return value === FALSE_TEXT ? false : null;
}
