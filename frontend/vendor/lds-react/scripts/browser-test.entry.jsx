// Mounted into a real page and driven by Playwright (browser-test.mjs) — the
// half of this package that can't be checked by rendering to a string:
// useControllerMount's mount-once/update-after DOM lifecycle, delegated
// click handlers actually firing a real React state update, and the
// re-render-without-remount behaviour the stateful five exist for.
//
// Each scenario sits in its own container <div id="…"> that THIS harness
// owns, rather than passing `id` down to the LDS component itself — where
// on the rendered markup an `id` prop lands is inconsistent from one
// template to the next (Modal doesn't forward it at all; CodeField forwards
// it onto every digit input), which is a vanilla-template detail this test
// has no need to depend on.
import React, { useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Banner, Chip, CodeField, Menu, Modal, SegmentedControl, Table, Tag, Textarea,
  Tooltip, Button, ToastProvider, useToast,
} from '../src/index.jsx';

// A ref should resolve to the REAL rendered root (the actual <button>), not
// the internal `display: contents` wrapper div — see useForwardRootRef's
// doc comment in runtime.jsx. Exposed on window for the test to inspect,
// since there's no other outward-facing way to observe a ref's target.
function RefProbe() {
  const ref = useRef(null);
  window.__refProbeTagName = () => (ref.current ? ref.current.tagName : null);
  return React.createElement(Button, { ref, id: 'ref-probe' }, 'Probe');
}

function ToastButtons() {
  const { toast } = useToast();
  return React.createElement('button', {
    id: 'raise-toast',
    onClick: () => toast({ status: 'success', children: 'Saved.' }),
  }, 'Raise');
}

function App() {
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const [chipRemoved, setChipRemoved] = useState(false);
  const [chipClicked, setChipClicked] = useState(false);
  const [modalClosed, setModalClosed] = useState(false);
  const [modalBack, setModalBack] = useState(false);
  const [code, setCode] = useState('');
  const [segment, setSegment] = useState('a');
  const [textareaValue, setTextareaValue] = useState('');
  const [unrelatedTick, setUnrelatedTick] = useState(0);
  const [nestedClicked, setNestedClicked] = useState(false);

  // Exposed for the test to call directly (window.__bumpTick()) rather than
  // clicking a button — a real click moves DOM focus to the button itself,
  // which would make "did the textarea keep focus" untestable regardless of
  // anything this package does.
  window.__bumpTick = () => setUnrelatedTick((n) => n + 1);

  return React.createElement(React.Fragment, null,
    React.createElement('div', { id: 'out-banner-dismissed' }, String(bannerDismissed)),
    React.createElement('div', { id: 'banner' }, !bannerDismissed && React.createElement(Banner, {
      dismissible: true, onDismiss: () => setBannerDismissed(true),
    }, 'Heads up')),

    React.createElement('div', { id: 'out-chip-removed' }, String(chipRemoved)),
    React.createElement('div', { id: 'out-chip-clicked' }, String(chipClicked)),
    React.createElement('div', { id: 'chip' }, !chipRemoved && React.createElement(Chip, {
      onClick: () => setChipClicked(true), onRemove: () => setChipRemoved(true),
    }, 'Tag')),

    React.createElement('div', { id: 'out-modal-closed' }, String(modalClosed)),
    React.createElement('div', { id: 'out-modal-back' }, String(modalBack)),
    React.createElement('div', { id: 'modal' }, React.createElement(Modal, {
      title: 'T', onClose: () => setModalClosed(true), onBack: () => setModalBack(true),
    }, 'Body')),

    React.createElement('div', { id: 'out-code' }, code),
    React.createElement('div', { id: 'codefield' }, React.createElement(CodeField, { length: 4, onChange: setCode })),

    React.createElement('div', { id: 'out-segment' }, segment),
    React.createElement('div', { id: 'segctrl' }, React.createElement(SegmentedControl, {
      name: 'seg', options: ['a', 'b', 'c'], value: segment, onChange: setSegment,
    })),

    React.createElement('div', { id: 'out-textarea' }, textareaValue),
    React.createElement('div', { id: 'out-tick' }, String(unrelatedTick)),
    React.createElement('div', { id: 'textarea' }, React.createElement(Textarea, {
      value: textareaValue, onChange: (e) => setTextareaValue(e.target.value),
    })),

    React.createElement('div', { id: 'tooltip' }, React.createElement(Tooltip, { label: 'A label' },
      React.createElement('span', { id: 'tooltip-trigger' }, React.createElement(Button, null, 'Hover me')))),

    React.createElement('div', { id: 'out-nested-clicked' }, String(nestedClicked)),
    React.createElement('div', { id: 'nested' }, React.createElement(Chip, null,
      React.createElement(Button, {
        id: 'nested-button', onClick: () => setNestedClicked(true),
      }, 'Nested'))),

    React.createElement(RefProbe, null),

    // A JSX icon/cell value in a list-shaped field must actually render —
    // not stringify to "[object Object]" (see components.jsx's Menu/Table
    // listSlotKeys comments for the bug this locks in).
    React.createElement('div', { id: 'menu-jsx-icon' }, React.createElement(Menu, {
      items: [{ label: 'Rename', icon: React.createElement('b', { className: 'probe-icon' }, 'i') }],
    })),
    React.createElement('div', { id: 'table-jsx-cell' }, React.createElement(Table, {
      columns: [{ key: 'status', label: 'Status' }],
      rows: [{ status: React.createElement(Tag, { hue: 'green' }, 'Active') }],
    })),

    React.createElement(ToastProvider, null, React.createElement(ToastButtons, null)));
}

createRoot(document.getElementById('root')).render(React.createElement(App));
