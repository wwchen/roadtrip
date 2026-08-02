import assert from 'node:assert/strict';
import test from 'node:test';

import { AUTH_CHANGED_EVENT, notifyAuthChanged, onAuthChanged } from './auth-events.js';

test('onAuthChanged receives notifyAuthChanged and unsubscribes', () => {
  const events = [];
  globalThis.window = new EventTarget(); // module uses window.*
  const off = onAuthChanged(() => events.push(1));
  notifyAuthChanged();
  assert.equal(events.length, 1);
  assert.equal(AUTH_CHANGED_EVENT, 'roadtrip:auth-changed');
  off();
  notifyAuthChanged();
  assert.equal(events.length, 1);
  delete globalThis.window;
});
