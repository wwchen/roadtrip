import assert from 'node:assert/strict';
import test from 'node:test';

import { isUnauthorized, handleSubmit, _setFormCtrlForTest, _getModuleState } from './watches-page.js';

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

test('REAL handleSubmit 401: finally guard prevents form repaint (REGRESSION TEST)', async () => {
  // REGRESSION TEST for task-19 bug: handleSubmit's finally block was unconditionally
  // calling formCtrl.setLoading(false), which re-rendered the form over the signed-out
  // message after a 401.
  //
  // This test drives the ACTUAL REAL handleSubmit function (exported from watches-page.js)
  // with a 401-throwing API and a fake form controller.
  //
  // Expected behavior WITH the guard `if (!signedOut && formCtrl) formCtrl.setLoading(false)`:
  // - renderSignedOut() sets signedOut=true and formCtrl=null
  // - the finally guard evaluates to false
  // - setLoading(false) is NOT called
  // - loadingCalls contains only [true] from the start, not [true, false]
  //
  // WITHOUT the guard (reverted to `formCtrl.setLoading(false)`):
  // - the finally block tries to call null.setLoading(false)
  // - throws TypeError: Cannot read properties of null
  // - this test FAILS

  const fakeFormHost = { innerHTML: '<form>...previous watch...</form>' };
  const fakeTableHost = { innerHTML: '' };
  const fakeBannerHost = { innerHTML: '' };

  const originalDocument = global.document;
  global.document = {
    getElementById: (id) => {
      if (id === 'form-host') return fakeFormHost;
      if (id === 'table-host') return fakeTableHost;
      if (id === 'banner-host') return fakeBannerHost;
      return null;
    },
  };

  const loadingCalls = [];
  const fakeFormCtrl = {
    setLoading: (val) => { loadingCalls.push(val); },
    setError: () => {},
    getEditingId: () => null,
    setMode: () => {},
    dispose: () => {},
  };

  _setFormCtrlForTest(fakeFormCtrl);

  const fakeDeps = {
    createWatch: async () => { throw { status: 401 }; },
    updateWatch: async () => { throw { status: 401 }; },
    loadWatches: async () => {},
    notifyWatchesChanged: () => {},
    showBanner: () => {},
  };

  try {
    await handleSubmit({ poi_id: 42, start_date: '2024-01-01' }, fakeDeps);

    const state = _getModuleState();

    // Assert: formCtrl is null (renderSignedOut nulled it)
    assert.equal(state.formCtrl, null, 'formCtrl should be null after 401');

    // Assert: signedOut is true (renderSignedOut set it)
    assert.equal(state.signedOut, true, 'signedOut should be true after 401');

    // Assert: form-host is empty (not repainted)
    assert.equal(fakeFormHost.innerHTML, '', 'form-host should stay empty, not repainted');

    // Assert: setLoading was called once (true at start), NOT twice (true then false)
    // The finally guard prevents the second call
    assert.equal(loadingCalls.length, 1, 'setLoading should only be called once (at start)');
    assert.equal(loadingCalls[0], true, 'first setLoading call should be true');

    // If the guard is removed from the REAL handleSubmit, this test throws TypeError
    // when trying to call null.setLoading(false)

  } finally {
    global.document = originalDocument;
  }
});

test('REAL handleSubmit non-401 error: form shows error and setLoading runs (regression guard)', async () => {
  // Verify that non-401 errors don't trigger renderSignedOut and the finally block
  // still calls setLoading(false) to show the error state.

  const fakeFormHost = { innerHTML: '<form>...</form>' };
  const fakeTableHost = { innerHTML: '' };
  const fakeBannerHost = { innerHTML: '' };

  const originalDocument = global.document;
  global.document = {
    getElementById: (id) => {
      if (id === 'form-host') return fakeFormHost;
      if (id === 'table-host') return fakeTableHost;
      if (id === 'banner-host') return fakeBannerHost;
      return null;
    },
  };

  const loadingCalls = [];
  let errorSet = null;
  const fakeFormCtrl = {
    setLoading: (val) => { loadingCalls.push(val); },
    setError: (msg) => { errorSet = msg; },
    getEditingId: () => null,
    setMode: () => {},
    dispose: () => {},
  };

  _setFormCtrlForTest(fakeFormCtrl);

  const fakeDeps = {
    createWatch: async () => { throw { status: 500, message: 'Server error' }; },
    updateWatch: async () => { throw { status: 500 }; },
    loadWatches: async () => {},
    notifyWatchesChanged: () => {},
    showBanner: () => {},
  };

  try {
    await handleSubmit({ poi_id: 42, start_date: '2024-01-01' }, fakeDeps);

    const state = _getModuleState();

    // Assert: formCtrl is NOT null (still valid for non-401 errors)
    assert.notEqual(state.formCtrl, null, 'formCtrl should still exist after non-401 error');

    // Assert: signedOut is false (not triggered for 500)
    assert.equal(state.signedOut, false, 'signedOut should be false after non-401 error');

    // Assert: form-host still has content (not cleared)
    assert.equal(fakeFormHost.innerHTML, '<form>...</form>', 'form should remain visible');

    // Assert: setLoading was called twice: true at start, false in finally
    assert.equal(loadingCalls.length, 2, 'setLoading should be called twice (start + finally)');
    assert.equal(loadingCalls[0], true, 'first setLoading call should be true');
    assert.equal(loadingCalls[1], false, 'second setLoading call should be false');

    // Assert: error was set
    assert.equal(errorSet, 'Server error', 'error should be set on the form controller');

  } finally {
    global.document = originalDocument;
  }
});
