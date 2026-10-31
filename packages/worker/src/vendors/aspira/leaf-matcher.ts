import type { MatchPolicy } from '../../registry/poi-registry.js';
import type { AspiraLeaf } from './leaf.js';
import type { AspiraLeafMatch, AspiraLeafMatchKind } from './leaf-match.js';
import { jaccard, normalize, tokensOf } from './normalize.js';

const LOGGED_MISS_SAMPLES = 5;
const NO_SCORE = 0;

export interface AspiraLeafTally {
  exact: number;
  fuzzy: number;
  parent: number;
  miss: number;
  skippedContainer: number;
  skippedNonBookable: number;
  missSamples: string[];
}

export interface AspiraLeafMatchResult<T> {
  matches: AspiraLeafMatch<T>[];
  tally: AspiraLeafTally;
}

interface IndexEntry<T> {
  key: string;
  tokens: ReadonlySet<string>;
  value: T;
}

export class AspiraLeafMatcher<T> {
  private readonly tokenIndex: IndexEntry<T>[];

  constructor(
    private readonly byName: ReadonlyMap<string, T>,
    private readonly nonBookableResourceLocationIds: ReadonlySet<bigint>,
    private readonly policy: MatchPolicy,
  ) {
    this.tokenIndex = [...byName].map(([key, value]) => ({ key, tokens: tokensOf(key), value }));
  }

  matchBookable(leaves: readonly AspiraLeaf[]): AspiraLeafMatchResult<T> {
    const matches: AspiraLeafMatch<T>[] = [];
    const countByKind = new Map<AspiraLeafMatchKind, number>();
    let miss = 0;
    let skippedContainer = 0;
    let skippedNonBookable = 0;
    const missSamples: string[] = [];

    for (const leaf of leaves) {
      const resourceLocationId = leaf.resourceLocationId;
      if (resourceLocationId === null) {
        skippedContainer += 1;
        continue;
      }
      if (this.nonBookableResourceLocationIds.has(resourceLocationId)) {
        skippedNonBookable += 1;
        continue;
      }
      const match = this.match(leaf);
      if (match === null) {
        miss += 1;
        if (missSamples.length < LOGGED_MISS_SAMPLES) missSamples.push(leaf.name);
        continue;
      }
      countByKind.set(match.kind, (countByKind.get(match.kind) ?? 0) + 1);
      matches.push(match);
    }

    return {
      matches,
      tally: {
        exact: countByKind.get('exact') ?? 0,
        fuzzy: countByKind.get('fuzzy') ?? 0,
        parent: countByKind.get('parent') ?? 0,
        miss,
        skippedContainer,
        skippedNonBookable,
        missSamples,
      },
    };
  }

  match(leaf: AspiraLeaf): AspiraLeafMatch<T> | null {
    const key = normalize(leaf.name);
    if (this.byName.has(key)) {
      return { leaf, value: this.byName.get(key) as T, kind: 'exact', matchedName: key, score: null };
    }

    const tokens = tokensOf(key);
    let best: IndexEntry<T> | null = null;
    let bestScore = NO_SCORE;
    for (const entry of this.tokenIndex) {
      const score = jaccard(entry.tokens, tokens);
      if (best === null || score > bestScore) {
        best = entry;
        bestScore = score;
      }
    }
    if (best !== null && bestScore >= this.policy.fuzzyThreshold) {
      return { leaf, value: best.value, kind: 'fuzzy', matchedName: best.key, score: bestScore };
    }

    if (!this.policy.parentFallback) return null;
    if (leaf.parentName === null) return null;
    const parentKey = normalize(leaf.parentName);
    if (!this.byName.has(parentKey)) return null;
    return { leaf, value: this.byName.get(parentKey) as T, kind: 'parent', matchedName: parentKey, score: null };
  }
}
