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

test('modalTemplate marks the handle and header as drag regions, but not the body', () => {
  const html = modalTemplate({ title: 'Sign in', sheetOnMobile: true });
  // A drag may only start where the content does not scroll.
  assert.match(html, /class="rt-modal-grab-handle" data-modal-drag/);
  assert.match(html, /class="rt-modal-header" data-modal-drag/);
  assert.doesNotMatch(html, /rt-modal-body" data-modal-drag/);
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

test('setBody appends element into the modal body host', () => {
  const originalDocument = globalThis.document;

  // Fake body host that records appendChild calls
  let appendedEl = null;
  const fakeBodyHost = {
    innerHTML: '',
    appendChild(el) { appendedEl = el; },
  };

  globalThis.document = {
    getElementById() { return null; },
    createElement(tagName) { return { id: '', rel: '', href: '', tagName }; },
    head: { appendChild() {} },
    activeElement: null,
    // querySelector presence enables the bodyHost resolution branch in modal.js
    querySelector() { return null; },
    addEventListener() {},
    removeEventListener() {},
  };

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
    querySelector(sel) {
      // Resolve the body host selector; return null for focus-trap queries.
      if (sel === '[data-modal-body]') return fakeBodyHost;
      return null;
    },
  };

  try {
    const controller = mountModal(host, { title: 'Test' });

    const fakeContent = { nodeType: 1, tagName: 'DIV' };
    controller.setBody(fakeContent);

    assert.strictEqual(appendedEl, fakeContent, 'setBody should append the element into the body host');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

/**
 * The ✕, the backdrop and Escape all funnel through close(), which only invokes
 * onClose. A caller that omits it gets a modal whose every dismissal affordance
 * is inert — which is exactly what shipped in the login card. These pin the
 * wiring so the next caller cannot repeat it silently.
 */
test('the close button, the backdrop and Escape each invoke onClose', () => {
  const originalDocument = globalThis.document;
  let keydownHandler = null;
  globalThis.document = {
    getElementById() { return null; },
    createElement(tagName) { return { id: '', rel: '', href: '', tagName }; },
    head: { appendChild() {} },
    addEventListener(type, fn) { if (type === 'keydown') keydownHandler = fn; },
    removeEventListener() {},
  };

  let clickHandler = null;
  const host = {
    innerHTML: '',
    addEventListener(type, fn) { if (type === 'click') clickHandler = fn; },
    removeEventListener() {},
  };

  try {
    let closes = 0;
    mountModal(host, { title: 'X', onClose: () => { closes += 1; } });

    // Header ✕
    clickHandler({ target: { closest: (sel) => (sel === '[data-modal-close]' ? {} : null) } });
    assert.equal(closes, 1, 'the ✕ should close');

    // Scrim
    clickHandler({ target: { closest: () => null, dataset: { modalBackdrop: '' } } });
    assert.equal(closes, 2, 'a tap outside the card should close');

    // Escape
    keydownHandler({ key: 'Escape' });
    assert.equal(closes, 3, 'Escape should close');

    // Anything else must not.
    clickHandler({ target: { closest: () => null, dataset: {} } });
    assert.equal(closes, 3, 'a tap on the card itself should not close');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('closeOnBackdrop false keeps a backdrop tap from closing, but not the ✕', () => {
  const originalDocument = globalThis.document;
  globalThis.document = {
    getElementById() { return null; },
    createElement(tagName) { return { id: '', rel: '', href: '', tagName }; },
    head: { appendChild() {} },
    addEventListener() {},
    removeEventListener() {},
  };

  let clickHandler = null;
  const host = {
    innerHTML: '',
    addEventListener(type, fn) { if (type === 'click') clickHandler = fn; },
    removeEventListener() {},
  };

  try {
    let closes = 0;
    mountModal(host, { title: 'X', closeOnBackdrop: false, onClose: () => { closes += 1; } });

    clickHandler({ target: { closest: () => null, dataset: { modalBackdrop: '' } } });
    assert.equal(closes, 0, 'backdrop is opt-out');

    clickHandler({ target: { closest: (sel) => (sel === '[data-modal-close]' ? {} : null) } });
    assert.equal(closes, 1, 'the ✕ always closes');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});
