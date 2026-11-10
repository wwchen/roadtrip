export const RETURN_TO_PARAM = 'return_to';
export const HOME_PATH = '/';

const PATH_START = '/';
const PROTOCOL_RELATIVE_START = '//';
const BACKSLASH = '\\';
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

function isSameSitePath(value: string): boolean {
  return (
    value.startsWith(PATH_START) &&
    !value.startsWith(PROTOCOL_RELATIVE_START) &&
    !value.includes(BACKSLASH) &&
    !CONTROL_CHARACTERS.test(value)
  );
}

export function safeReturnTo(raw: string | null | undefined): string {
  if (!raw) return HOME_PATH;
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return HOME_PATH;
  }
  return isSameSitePath(raw) && isSameSitePath(decoded) ? raw : HOME_PATH;
}

export function withReturnTo(path: string, returnTo: string): string {
  return `${path}?${new URLSearchParams({ [RETURN_TO_PARAM]: returnTo })}`;
}
