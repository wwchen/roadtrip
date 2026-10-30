export class UnsupportedIdError extends Error {
  constructor(raw: unknown) {
    super(`expected an id as string, bigint or number, got ${raw === null ? 'null' : typeof raw}`);
    this.name = 'UnsupportedIdError';
  }
}

export function idString(raw: unknown): string {
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'bigint' || typeof raw === 'number') return String(raw);
  throw new UnsupportedIdError(raw);
}
