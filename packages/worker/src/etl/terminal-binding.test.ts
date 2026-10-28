import { expectTypeOf, test } from 'vitest';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import type { AnyTerminalEtlBinding, TerminalEtlBinding } from './terminal-binding.js';

test('a typed binding registers as an erased binding without a cast', () => {
  expectTypeOf<TerminalEtlBinding<string, CampgroundUpsertCandidate>>().toExtend<AnyTerminalEtlBinding>();
});
