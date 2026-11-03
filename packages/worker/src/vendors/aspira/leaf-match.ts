import type { GeometryProvenance } from '@roadtrip/domain';
import type { GeometryPoint } from './geometry-point.js';
import type { AspiraLeaf } from './leaf.js';

export type AspiraLeafMatchKind = 'exact' | 'fuzzy' | 'parent';

export interface AspiraLeafMatch<T> {
  leaf: AspiraLeaf;
  value: T;
  kind: AspiraLeafMatchKind;
  matchedName: string;
  score: number | null;
}

export function toProvenance(match: AspiraLeafMatch<GeometryPoint>): GeometryProvenance {
  return {
    match_kind: match.kind,
    source: match.value.source,
    matched_name: match.matchedName,
    ...(match.score === null ? {} : { score: match.score }),
  };
}
