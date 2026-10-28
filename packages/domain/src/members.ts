export function memberList<const T extends Record<string, string>>(vocabulary: T): readonly T[keyof T][] {
  return Object.freeze(Object.values(vocabulary)) as readonly T[keyof T][];
}
