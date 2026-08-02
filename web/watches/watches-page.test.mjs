import assert from 'node:assert/strict';
import test from 'node:test';

import { isUnauthorized } from './watches-page.js';

test('isUnauthorized detects a 401', () => {
  assert.equal(isUnauthorized({ status: 401 }), true);
  assert.equal(isUnauthorized({ status: 404 }), false);
  assert.equal(isUnauthorized(null), false);
});
