import assert from 'node:assert/strict';
import test from 'node:test';

import { isUnauthorized } from './watches-page.js';

test('isUnauthorized detects a 401', () => {
  assert.equal(isUnauthorized({ status: 401 }), true);
  assert.equal(isUnauthorized({ status: 404 }), false);
  assert.equal(isUnauthorized(null), false);
});

test('isUnauthorized drives signed-out guard logic', () => {
  // Test that the detection helper used in loadWatches and mutate handlers
  // correctly identifies 401s. The actual guard (signedOut flag + applyUrlAction skip)
  // is module-private, but this confirms the decision predicate.
  const unauth = { status: 401, message: 'Unauthorized' };
  const notFound = { status: 404 };
  const networkError = new Error('network');

  assert.equal(isUnauthorized(unauth), true, 'should detect 401 error');
  assert.equal(isUnauthorized(notFound), false, 'should not trigger on 404');
  assert.equal(isUnauthorized(networkError), false, 'should not trigger on generic error');
});
