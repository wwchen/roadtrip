export type AuthSearchParams = Promise<Record<string, string | string[] | undefined>>;

export function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
