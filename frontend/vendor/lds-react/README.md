# @lew/lds-react

React components over [`@lew/lds`](../lds) — for a React app (a Claude Design canvas, a Claude Code scaffold, a future React consumer) to build with real LDS components and real props, without `@lew/lds` itself needing React.

`@lew/lds` is framework-free: every component is `(props) => htmlString`; the five stateful ones ship a `mountX(el, config) -> {dispose(), update()}` controller alongside. This package doesn't change any of that — it's a separate, additive layer that wraps it. **No existing consumer of `@lew/lds` needs to change anything** — see "What this means for other apps" below.

## Usage

```jsx
import { Button, Modal, TextField } from '@lew/lds-react';
import '@lew/lds/css';

function ConfirmDialog({ onClose }) {
  return (
    <Modal title="Delete this?" onClose={onClose}>
      <TextField label="Type DELETE to confirm" />
      <Button variant="primary" onClick={onClose}>Confirm</Button>
    </Modal>
  );
}
```

Every component from `@lew/lds`'s templates has a matching PascalCase export here (`button` → `Button`, `textField` → `TextField`, …), plus the five stateful ones and a `ToastProvider`/`useToast()` pair for Toast (see below). Import the same CSS you already import for `@lew/lds` — this package carries no styles of its own.

### Composition

A `Slot` prop (`children`, `title`, `actions`, `icon`, …) accepts a React node, not just text:

```jsx
<ButtonGroup>
  <Button variant="secondary" onClick={onCancel}>Cancel</Button>
  <Button variant="primary" onClick={onConfirm}>Confirm</Button>
</ButtonGroup>
```

A nested wrapped component keeps its own event handlers — clicking "Confirm" above really does fire `onConfirm`, because composition uses a real `createPortal`, not a flatten-to-string. (An earlier version of this package tried flattening slot content with `renderToStaticMarkup`; that broke on exactly this case — nesting a hook-using component inside another's render corrupts React's render dispatcher, since it's not reentrant. See `src/runtime.jsx`'s doc comments for the mechanism that replaced it.)

**List-shaped props compose too** — Menu's `items[].label/icon/hint`, Table's `columns[].label`/every `rows[]` cell, Tabs' `tabs[].label/section`, and Select's top-level `options[].label` all accept a React node the same way a flat slot prop does:

```jsx
<Menu items={[{ label: 'Delete', icon: <TrashIcon/>, danger: true }]} />
<Table columns={[{ key: 'status', label: 'Status' }]} rows={[{ status: <Tag hue="green">Active</Tag> }]} />
```

**One level of nesting is the actual limit**: an option nested inside one of Select's own `{label, options}` groups isn't reached (SegmentedControl's `options[].label` is a plain string in the vanilla type, not a slot, so there's nothing to convert there either). Call the exported `toSlot()` yourself on a field this doesn't reach:

```jsx
<Select options={[{ label: 'Region', options: [{ value: 'us', label: toSlot(<FlagUS/>) }] }]} />
```

### Refs

Every component forwards a ref to its real rendered root — `<Button ref={r}>` gives you the actual `<button>`/`<a>` element, not an internal wrapper, so `.focus()`, measuring, and the like work as expected. (Internally, each component renders inside a `display: contents` div so it participates in the parent's layout normally; the ref is redirected past that wrapper to the real element underneath.)

### Form controls

`Checkbox`, `Radio`, `Toggle`, `TextField`, `Select` wire `onChange` for you (`input` events for text-like controls, `change` for checkbox/radio/select — the same split React itself makes). Their initial `value`/`checked` comes from props at first render; this package does not force the DOM back to a controlled value on every render, because for `Textarea`/`CodeField` that's exactly what would destroy the focused element on every keystroke (see below).

### The five stateful components

`CodeField`, `SegmentedControl`, `Textarea`, `Tooltip` are controller-backed — mounted once, patched via `update()` on later renders, same as their vanilla `mountX` counterparts. Typing into `Textarea`/`CodeField` doesn't call `update()` at all for a self-inflicted change (see `useSelfChangeFlag` in `src/runtime.jsx`) — the vanilla controller's own keystroke handling already patches the live DOM in place; calling `update()` on top of that would replace the focused element for no reason.

`SegmentedControl`'s and `CodeField`'s `onChange` receive the resolved value directly (`(value: string) => void`), not an event — that's the vanilla controller's own API, carried through rather than reshaped to look like every other control.

### Toast

Toast is global chrome, not a per-instance component — mount one `<ToastProvider>` near your root and raise messages through `useToast()`:

```jsx
function App() {
  return (
    <ToastProvider>
      <YourApp/>
    </ToastProvider>
  );
}

function SaveButton() {
  const { toast } = useToast();
  return <Button onClick={() => toast({ status: 'success', children: 'Saved.' })}>Save</Button>;
}
```

## What this means for other apps using `@lew/lds`

**Nothing changes for anyone today.** `@lew/lds` — templates, controllers, CSS, the `exports` map — is untouched by this package. Specifically:

- **Roadtrip** (the real first consumer, plain vanilla JS with no build step) needs zero changes. It doesn't depend on this package and never will unless it specifically wants to.
- **Any other framework-free consumer** is in the same position — this is a new, separate `packages/lds-react` workspace member; it doesn't touch `packages/lds`.
- **A future React consumer** (Claude Design's synced project, a Claude Code scaffold, or a genuinely new React app) installs `@lew/lds-react` alongside `@lew/lds` and gets real components. Nothing to migrate — there's no prior React version of these components still in use anywhere in this repo (`project/` holds the historical pre-productionization export; it's explicitly not built, see `docs/build.mjs`'s comment on it).

**The ongoing cost is keeping this package in sync**, not any one-time migration: a new component or prop added to `@lew/lds` needs a matching entry in `components.jsx`'s config table (most are a few lines — a template's own `Props` interface already enumerates its `Slot` fields) or, for a new stateful component, a hand-written wrapper alongside the other four in `controllers.jsx`. `npm test -w @lew/lds-react` (SSR smoke test + a real-browser Playwright suite) is meant to catch drift quickly, not to be run only occasionally.

## Testing

- `scripts/smoke-test.mjs` — renders every wrapper via `react-dom/server`, checks the resulting markup.
- `scripts/browser-test.mjs` — drives the five stateful components and delegated-event components in a real Chromium page via Playwright: focus movement, event delegation (a click on Chip's remove button doesn't also fire its own `onClick`), the self-change-vs-external-change distinction that keeps typing from losing focus, and nested-composition interactivity.

Both run via `npm test -w @lew/lds-react`, and as part of the monorepo's own `npm test`.
