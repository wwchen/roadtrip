import assert from 'node:assert/strict';
import test from 'node:test';

import { tabsTemplate } from './tabs-template.js';
import { mountTabs } from './tabs.js';

// ── Pure template tests ──────────────────────────────────────────────────────

test('tabsTemplate renders a [data-tab] button per tab', () => {
  const html = tabsTemplate({
    tabs: [
      { id: 'profile', label: 'Profile' },
      { id: 'security', label: 'Security' },
    ],
    active: 'profile',
  });
  assert.match(html, /data-tab="profile"/);
  assert.match(html, /data-tab="security"/);
});

test('tabsTemplate marks the active tab with aria-selected="true"', () => {
  const html = tabsTemplate({
    tabs: [
      { id: 'a', label: 'Alpha' },
      { id: 'b', label: 'Beta' },
    ],
    active: 'b',
  });
  // active tab has aria-selected="true"
  assert.match(html, /data-tab="b"[^>]*aria-selected="true"/);
  // inactive tab has aria-selected="false"
  assert.match(html, /data-tab="a"[^>]*aria-selected="false"/);
});

test('tabsTemplate applies rt-tabs-tab--active class to the active tab', () => {
  const html = tabsTemplate({
    tabs: [{ id: 'x', label: 'X' }, { id: 'y', label: 'Y' }],
    active: 'x',
  });
  assert.match(html, /rt-tabs-tab--active/);
});

test('tabsTemplate escapes tab labels', () => {
  const html = tabsTemplate({
    tabs: [{ id: 'xss', label: '<script>alert(1)</script>' }],
    active: 'xss',
  });
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('tabsTemplate renders all tab labels', () => {
  const html = tabsTemplate({
    tabs: [
      { id: 'login', label: 'Login' },
      { id: 'password', label: 'Password' },
      { id: 'notifications', label: 'Notifications' },
    ],
    active: 'login',
  });
  assert.match(html, /Login/);
  assert.match(html, /Password/);
  assert.match(html, /Notifications/);
});

test('tabsTemplate wraps in rt-tabs-rail container', () => {
  const html = tabsTemplate({
    tabs: [{ id: 'a', label: 'A' }],
    active: 'a',
  });
  assert.match(html, /rt-tabs-rail/);
});

// ── Stub-mount tests ─────────────────────────────────────────────────────────

function makeStubDocument() {
  let injectedLink = null;
  return {
    getElementById() { return null; },
    createElement(tagName) {
      const el = { id: '', rel: '', href: '', tagName };
      if (tagName === 'link') injectedLink = el;
      return el;
    },
    head: {
      appendChild(el) { injectedLink = el; },
    },
    addEventListener() {},
    removeEventListener() {},
    _getInjectedLink() { return injectedLink; },
  };
}

test('stub-mount: mountTabs sets host.innerHTML and injects stylesheet', () => {
  const originalDocument = globalThis.document;
  const stubDoc = makeStubDocument();
  globalThis.document = stubDoc;

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const tabs = [
      { id: 'profile', label: 'Profile' },
      { id: 'security', label: 'Security' },
    ];
    mountTabs(host, { tabs, active: 'profile' });

    assert.match(host.innerHTML, /data-tab="profile"/);
    assert.match(host.innerHTML, /data-tab="security"/);

    const link = stubDoc._getInjectedLink();
    assert.ok(link, 'link element should have been injected');
    assert.match(link.href, /tabs\.css/);
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: getActive() returns the initial active tab', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const tabs = [
      { id: 'profile', label: 'Profile' },
      { id: 'security', label: 'Security' },
    ];
    const ctrl = mountTabs(host, { tabs, active: 'security' });
    assert.equal(ctrl.getActive(), 'security');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: setActive(id) updates state, re-renders, and marks new active', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const tabs = [
      { id: 'profile', label: 'Profile' },
      { id: 'security', label: 'Security' },
    ];
    const ctrl = mountTabs(host, { tabs, active: 'profile' });

    assert.equal(ctrl.getActive(), 'profile');

    ctrl.setActive('security');

    assert.equal(ctrl.getActive(), 'security');
    // After re-render, security should be active
    assert.match(host.innerHTML, /data-tab="security"[^>]*aria-selected="true"/);
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: setActive calls onChange callback', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const tabs = [
      { id: 'profile', label: 'Profile' },
      { id: 'security', label: 'Security' },
    ];
    let calledWith = null;
    const ctrl = mountTabs(host, {
      tabs,
      active: 'profile',
      onChange(id) { calledWith = id; },
    });

    ctrl.setActive('security');
    assert.equal(calledWith, 'security');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: dispose clears host.innerHTML and removes listener', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  let removedListener = false;
  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() { removedListener = true; },
  };

  try {
    const tabs = [{ id: 'a', label: 'A' }];
    const ctrl = mountTabs(host, { tabs, active: 'a' });
    ctrl.dispose();
    assert.equal(host.innerHTML, '');
    assert.ok(removedListener, 'removeEventListener should have been called');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});
