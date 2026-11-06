const LINKABLE_PROTOCOLS: readonly string[] = ['http:', 'https:'];

export function safeLink(raw: string): string | null {
  try {
    return LINKABLE_PROTOCOLS.includes(new URL(raw).protocol) ? raw : null;
  } catch {
    return null;
  }
}
