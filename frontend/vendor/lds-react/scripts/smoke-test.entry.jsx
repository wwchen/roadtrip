// Renders every wrapped component through react-dom/server and checks the
// markup it produces contains what the vanilla template would produce for
// the same props — not a byte-for-byte diff (the wrapper's dangerouslySetInnerHTML
// re-parses and re-serialises via jsdom-less renderToStaticMarkup, which can
// reorder void-element self-closing syntax), but a real check that nothing
// crashes and the right classes/text land in the DOM.
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import * as LR from '../src/index.jsx';
import * as V from '@lew/lds';

// Every wrapper uses useLayoutEffect (to mount controllers, locate portal
// targets) — expected and harmless under renderToStaticMarkup, which never
// runs effects at all, but React warns on every such component regardless.
// This suite exists to catch real problems, not to re-litigate a documented
// React/SSR limitation this package isn't designed around (see
// scripts/browser-test.mjs for what actually needs a live DOM).
const realConsoleError = console.error;
console.error = (msg, ...args) => {
  if (typeof msg === 'string' && msg.includes('useLayoutEffect does nothing on the server')) return;
  realConsoleError(msg, ...args);
};

const h = React.createElement;
let count = 0;

function check(name, element, expectations) {
  const html = renderToStaticMarkup(element);
  for (const needle of expectations) {
    assert.ok(html.includes(needle), `${name}: expected markup to include ${JSON.stringify(needle)}\n${html}`);
  }
  count++;
}

// ---- stateless components, one representative render each -------------------

check('Avatar', h(LR.Avatar, { name: 'Ada Lovelace' }), ['lds-avatar', 'AL']);

check('Banner', h(LR.Banner, { status: 'error', title: 'Failed', children: 'Retry.', dismissible: true }),
  ['lds-banner', 'lds-banner--error', 'Failed', 'Retry.', 'lds-banner__dismiss']);

check('Button (plain text child)', h(LR.Button, { variant: 'primary' }, 'Save'),
  ['lds-btn', 'lds-btn--primary', 'Save']);

// A JSX child (rather than a plain string) is composed via a portal, not
// flattening to markup — see runtime.jsx's useSlotResolution/useSlotPortals
// doc comments. Portals render nothing during a server render (same as any
// React portal — this is standard, documented React behaviour, not a bug),
// so this only checks the placeholder slot and surrounding markup are
// sound; see scripts/browser-test.mjs for proof the real child actually
// lands and stays interactive once mounted in a browser.
check('Button (JSX child, placeholder present, no crash)', h(LR.Button, { variant: 'secondary' }, h('b', null, 'Bold')),
  ['lds-btn', 'lds-btn--secondary', 'data-lds-slot=']);

check('ButtonGroup composing two Buttons via children (placeholder present, no crash)', h(LR.ButtonGroup, null,
  h(LR.Button, { variant: 'secondary' }, 'Cancel'),
  h(LR.Button, { variant: 'primary' }, 'Confirm')),
  ['lds-btn-group', 'data-lds-slot=']);

check('Card selectable', h(LR.Card, { selectable: true, title: 'Plan', selected: true }),
  ['lds-card', 'lds-card--selectable', 'aria-pressed="true"', 'Plan']);

check('Checkbox', h(LR.Checkbox, { label: 'Accept', defaultChecked: true }),
  ['lds-check', 'checked=""', 'Accept']);

check('Chip removable', h(LR.Chip, { onRemove: () => {} }, 'Filter'),
  ['lds-chip', 'lds-chip__remove', 'Filter']);

check('EmptyState', h(LR.EmptyState, { icon: 'search', title: 'Nothing here' }),
  ['lds-empty', 'Nothing here']);

check('Icon', h(LR.Icon, { name: 'check', size: 24 }), ['lds-icon', '#check']);

check('Inline', h(LR.Inline, { status: 'success' }, 'Saved'), ['lds-inline', 'Saved']);

check('Link', h(LR.Link, { href: '/x', variant: 'standalone' }, 'Learn more'),
  ['lds-link', 'lds-link--standalone', 'Learn more']);

check('Menu', h(LR.Menu, { items: [{ label: 'Edit' }, { separator: true }, { label: 'Delete', danger: true }] }),
  ['lds-menu', 'Edit', 'lds-menu__item--danger', 'Delete']);

// A JSX icon in a list-shaped field composes via the same portal mechanism
// as a flat slot (see useListSlotResolution) — placeholder present, no
// "[object Object]" string coercion (the bug this was added to catch: the
// vanilla template's slot() stringifies anything it doesn't recognize).
// See browser-test.mjs for proof the icon actually renders once mounted.
{
  const html = renderToStaticMarkup(h(LR.Menu, { items: [{ label: 'Rename', icon: h('b', null, 'i') }] }));
  assert.ok(html.includes('data-lds-slot='), 'Menu with JSX icon: expected a portal placeholder');
  assert.ok(!html.includes('[object Object]'), 'Menu with JSX icon: stringified instead of composing');
  count++;
}

check('Modal with close/back', h(LR.Modal, { title: 'Confirm', onClose: () => {}, onBack: () => {} }, 'Body'),
  ['lds-modal', 'lds-modal__close', 'lds-modal__back', 'Confirm', 'Body']);

check('Nav bar with back', h(LR.Nav, { variant: 'bar', title: 'Settings', onBack: () => {} }),
  ['lds-nav--bar', 'lds-nav__back', 'Settings']);

check('Radio', h(LR.Radio, { label: 'Option A', name: 'g', defaultChecked: true }),
  ['type="radio"', 'Option A']);

check('Row interactive', h(LR.Row, { title: 'Item', href: '/y', chevron: true }),
  ['lds-row', 'lds-row--interactive', 'Item']);

check('Select', h(LR.Select, { label: 'Country', options: ['US', 'CA'] }),
  ['lds-field', '<option', 'US', 'CA']);

check('Skeleton', h(LR.Skeleton, { variant: 'title' }), ['lds-skeleton--title']);

check('Table', h(LR.Table, { columns: [{ key: 'a', label: 'A' }], rows: [{ a: '1' }] }),
  ['lds-table', '<th>A</th>', '<td>1</td>']);

// Same "[object Object]" bug as Menu's icon, for a cell value (a status
// badge is the natural real-world case — see this preview's own history).
{
  const html = renderToStaticMarkup(h(LR.Table, {
    columns: [{ key: 'status', label: 'Status' }],
    rows: [{ status: h(LR.Tag, { hue: 'green' }, 'Active') }],
  }));
  assert.ok(html.includes('data-lds-slot='), 'Table with JSX cell: expected a portal placeholder');
  assert.ok(!html.includes('[object Object]'), 'Table with JSX cell: stringified instead of composing');
  count++;
}

check('Tabs', h(LR.Tabs, { tabs: [{ id: 'x', label: 'X' }], active: 'x' }),
  ['lds-tabs', 'lds-tabs__tab--active', 'X']);

check('Tag', h(LR.Tag, { hue: 'blue' }, 'Beta'), ['lds-tag', 'hue-blue', 'Beta']);

check('Toast', h(LR.Toast, { status: 'error', title: 'Failed', dismissible: true }, 'Retry.'),
  ['lds-toast', 'Failed', 'Retry.', 'lds-toast__dismiss']);

check('TextField with endAction', h(LR.TextField, { label: 'Password', endAction: { icon: 'eye', label: 'Show', onClick: () => {} } }),
  ['lds-field', 'lds-field__adorn--action', 'Password']);

check('Toggle', h(LR.Toggle, { label: 'Notifications', defaultChecked: true }),
  ['lds-toggle', 'Notifications']);

// ---- stateless components: className passthrough sanity ---------------------

check('className passthrough', h(LR.Tag, { className: 'my-extra' }, 'X'), ['my-extra']);

// ---- parity spot-check against the vanilla template directly ----------------

{
  const vanillaHtml = V.button({ variant: 'primary', children: 'Save' });
  const wrapperHtml = renderToStaticMarkup(h(LR.Button, { variant: 'primary' }, 'Save'));
  assert.ok(wrapperHtml.includes('lds-btn--primary'), 'wrapper Button missing variant class');
  assert.ok(vanillaHtml.includes('lds-btn--primary'), 'sanity: vanilla button() missing variant class');
  count++;
}

// ---- toSlot exported and behaves -----------------------------------------

assert.equal(typeof LR.toSlot('plain text'), 'string', 'toSlot should pass primitives through');
assert.ok(LR.toSlot(h('b', null, 'x')) && typeof LR.toSlot(h('b', null, 'x')).__html === 'string', 'toSlot should flatten a React element to raw markup');
count++;

console.log(`smoke-test: ${count} checks passed (stateless components)`);
