/**
 * [table]'s entry for [key], or undefined for a key it has never heard of — a
 * status or reason a newer server added. A typed `table[key]` cannot say so: it
 * claims an entry exists, and an inherited key (`constructor`) would answer with
 * one. The one place that rule is spelled, so every lookup keyed by a value the
 * server sent degrades to its fallback instead of throwing or comparing as NaN.
 */
export function entryOf<V>(table: Readonly<Record<string, V>>, key: string): V | undefined {
  return Object.hasOwn(table, key) ? table[key] : undefined;
}
