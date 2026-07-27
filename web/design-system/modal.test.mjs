import assert from 'node:assert/strict';
import test from 'node:test';

import { modalTemplate } from './modal-template.js';
import { mountModal } from './modal.js';

test('modalTemplate renders title, close affordance, scrim, and a body host', () => {
  const html = modalTemplate({ title: 'Sign in', sheetOnMobile: true });
  assert.match(html, /data-modal-close/);   // header ✕
  assert.match(html, /data-modal-body/);     // host for setBody()
  assert.match(html, /Sign in/);
});

test('modalTemplate escapes the title', () => {
  assert.match(modalTemplate({ title: '<script>x</script>' }), /&lt;script&gt;/);
});

test('modalTemplate renders scrim element', () => {
  const html = modalTemplate({ title: 'Hello' });
  assert.match(html, /rt-modal-scrim/);
});

test('modalTemplate with sheetOnMobile includes sheet class', () => {
  const html = modalTemplate({ title: 'Hello', sheetOnMobile: true });
  assert.match(html, /rt-modal-sheet/);
});

test('stub-mount: mountModal sets host.innerHTML and injects stylesheet', () => {
  const originalDocument = globalThis.document;
  let injectedLink = null;
  globalThis.document = {
    getElementById() { return null; },
    createElement(tagName) {
      const el = { id: '', rel: '', href: '', tagName };
      if (tagName === 'link') { injectedLink = el; }
      return el;
    },
    head: {
      appendChild(el) { injectedLink = el; },
    },
    addEventListener() {},
    removeEventListener() {},
  };

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const controller = mountModal(host, {
      title: 'X',
      onClose: () => {},
    });

    assert.match(host.innerHTML, /data-modal-close/);
    assert.match(host.innerHTML, /data-modal-body/);
    assert.match(host.innerHTML, /X/);

    // Stylesheet injected
    assert.ok(injectedLink, 'link element should have been injected');
    assert.match(injectedLink.href, /modal\.css/);

    // dispose clears DOM
    controller.dispose();
    assert.equal(host.innerHTML, '');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});
