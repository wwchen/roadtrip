// Shared machinery behind every wrapped component.
//
// A vanilla LDS component is a pure function returning an HTML string; a
// controller is a `mount(el, config) -> {dispose(), update()}` that owns some
// imperative behaviour on top of one. Every wrapper below reduces to one of
// two shapes:
//
//   - a template: render the string, drop it in with dangerouslySetInnerHTML,
//     and delegate a handful of documented events (Modal's close button,
//     Banner's dismiss button, …) — the vanilla template already strips
//     `onX` props out of its own markup (see attrs.js), so accepting them
//     here and wiring them by hand is the React-side half of that contract.
//   - a controller: hand the container to `mountX`, and keep it in sync with
//     `update()` on every render after the first.
//
// Neither shape reconciles into the tree the way a normal React subtree
// does — same constraint the vanilla `h()` composition has (see its own
// doc comment: "not a virtual DOM, no reconciliation"). A wrapper's own
// root re-paints in full whenever its own props change; that's fine for
// presentational components, and the five stateful ones exist precisely
// because SOME components can't tolerate that (a re-rendered <textarea>
// loses the caret) — those get real controllers, not a fresh render.
import React, { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { renderToStaticMarkup } from 'react-dom/server';
import { raw } from '@lew/lds';

function isSlottable(value) {
  if (value === null || value === undefined || typeof value !== 'object') return false; // primitive
  if (typeof value.__html === 'string') return false; // already raw() — plain markup, nothing to compose
  return true; // a React element, or an array of them
}

/**
 * Flattens a React node to `raw()` markup via a real (nested) React render.
 *
 * Safe to call from an event handler or effect — NOT during another
 * component's render. React's render dispatcher is a single global; calling
 * `renderToStaticMarkup` while a render is already on the stack corrupts it
 * for whatever hook runs next (surfaces as "Invalid hook call" somewhere
 * unrelated). That's exactly why the template/controller wrappers below use
 * a portal instead of this for their own slot props — this is exported for
 * the cases that have no persistent DOM node to portal into: Toast's
 * imperative `toast()` call, or a field one level deeper than
 * `useListSlotResolution` walks (an option nested inside one of Select's own
 * `{label, options}` groups). Safe in both cases specifically because they
 * run from a click handler or a manual call outside any render.
 */
export function toSlot(value) {
  if (!isSlottable(value)) return value;
  return raw(renderToStaticMarkup(React.createElement(React.Fragment, null, value)));
}

function readPath(obj, path) {
  return path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

export function mergeRefs(...refs) {
  return (node) => {
    for (const r of refs) {
      if (!r) continue;
      if (typeof r === 'function') r(node);
      else r.current = node;
    }
  };
}

/**
 * Points a forwarded ref at the REAL rendered root — `containerRef`'s first
 * child — rather than the `display: contents` wrapper div itself. The
 * wrapper isn't in the layout or accessibility tree (that's the point of
 * `display: contents`), so a ref resolving to it can't be focused, measured,
 * or otherwise used as the DOM node a consumer asked for. Re-syncs after
 * every render (no dependency array) since a prop change replaces the real
 * root wholesale — same reasoning as `useSlotPortals`.
 */
export function useForwardRootRef(containerRef, forwardedRef) {
  useLayoutEffect(() => {
    if (!forwardedRef) return;
    const node = containerRef.current ? containerRef.current.firstElementChild : null;
    if (typeof forwardedRef === 'function') forwardedRef(node);
    else forwardedRef.current = node;
  });
}

/**
 * Resolves a component's `Slot` props for one render. A primitive or
 * already-`raw()` value passes straight through the props it composes below,
 * unmodified. A React node gets swapped for a placeholder marker — an empty
 * element the template renders exactly where the real content belongs — and
 * recorded in `portals`, so it can be projected in with a REAL `createPortal`
 * after mount rather than flattened to a string (see `toSlot`'s doc comment
 * for why not). This is what lets a nested interactive child — a `<Button
 * onClick>` passed as another wrapped component's `children` — keep working:
 * its own render call happens through React's ordinary tree, not a second
 * one-off render.
 */
export function useSlotResolution(props, slotKeys) {
  const uid = useId();
  const resolved = { ...props };
  const portals = [];
  for (const key of slotKeys) {
    if (!(key in props)) continue;
    const value = props[key];
    if (isSlottable(value)) {
      const id = `${uid}-${key}`;
      resolved[key] = raw(`<span data-lds-slot="${id}" style="display:contents"></span>`);
      portals.push({ id, node: value });
    }
  }
  return [resolved, portals];
}

/**
 * The same resolution as `useSlotResolution`, for an array-shaped prop whose
 * entries carry `Slot` fields — Menu's `items[].icon/label/hint`, Table's
 * `rows[]` (every cell, since column keys are caller-defined so there's no
 * fixed field list — pass `null` for `slotKeys` to check every field on
 * every entry), Select's `options[]` (including one level of `{label,
 * options}` groups). Every entry keeps its own placement in the array;
 * entries that need no resolution pass through as the exact same reference.
 */
export function useListSlotResolution(list, slotKeys) {
  const uid = useId();
  const portals = [];
  if (!Array.isArray(list)) return [list, portals];
  const resolved = list.map((item, i) => {
    if (item === null || typeof item !== 'object') return item;
    const keys = slotKeys ?? Object.keys(item);
    let changed = false;
    const out = { ...item };
    for (const key of keys) {
      if (!(key in item)) continue;
      const value = item[key];
      if (isSlottable(value)) {
        const id = `${uid}-i${i}-${key}`;
        out[key] = raw(`<span data-lds-slot="${id}" style="display:contents"></span>`);
        portals.push({ id, node: value });
        changed = true;
      }
    }
    return changed ? out : item;
  });
  return [resolved, portals];
}

/**
 * Projects each resolved slot's real React node into the placeholder the
 * template rendered for it. Re-locates placeholders after every commit
 * (`useLayoutEffect` with no dependency array) rather than gating on a
 * changed-markup signal, and only calls `setState` when a target actually
 * changed — returning the SAME Map when nothing did lets React bail out of
 * the extra render, which is what keeps this from looping. A prop change
 * replaces the DOM subtree wholesale (see the module doc), so the
 * placeholders themselves are new nodes each time; an unrelated re-render
 * leaves the existing ones in place, and this simply reuses them.
 */
export function useSlotPortals(ref, portals) {
  const [targets, setTargets] = useState(() => new Map());

  useLayoutEffect(() => {
    const wrapper = ref.current;
    if (!wrapper) return;
    setTargets((prev) => {
      let changed = portals.length !== prev.size;
      const next = new Map();
      for (const { id } of portals) {
        const el = wrapper.querySelector(`[data-lds-slot="${id}"]`);
        if (el) next.set(id, el);
        if (el !== prev.get(id)) changed = true;
      }
      return changed ? next : prev;
    });
  });

  return portals
    .map(({ id, node }) => { const el = targets.get(id); return el ? createPortal(node, el) : null; })
    .filter(Boolean);
}

/**
 * Delegates a handful of named DOM events off the wrapper's rendered root.
 * `specs` is `{ name, event, selector }[]`, most-specific first — `name` may
 * be a dotted path (`endAction.onClick`) for a handler nested in a prop
 * object. `selector: null` means "the root element itself". One listener per
 * event type; the first matching spec wins, so a click on a nested button
 * (e.g. Chip's remove button) doesn't also fire the root's own handler.
 *
 * Rebinds whenever the rendered markup changes, since a prop change replaces
 * the DOM subtree wholesale (see the module doc) rather than patching it.
 */
export function useDomHandlers(ref, html, specs, props) {
  const propsRef = useRef(props);
  propsRef.current = props;

  useEffect(() => {
    const wrapper = ref.current;
    const root = wrapper && wrapper.firstElementChild;
    if (!wrapper || !root || !specs.length) return undefined;

    const byEvent = new Map();
    for (const spec of specs) {
      if (!byEvent.has(spec.event)) byEvent.set(spec.event, []);
      byEvent.get(spec.event).push(spec);
    }

    const bound = [];
    for (const [event, list] of byEvent) {
      const listener = (e) => {
        for (const { name, selector } of list) {
          const matched = selector ? e.target.closest(selector) : root;
          if (matched && root.contains(matched)) {
            const fn = readPath(propsRef.current, name);
            if (typeof fn === 'function') fn(e);
            return;
          }
        }
      };
      wrapper.addEventListener(event, listener);
      bound.push([event, listener]);
    }
    return () => { for (const [event, listener] of bound) wrapper.removeEventListener(event, listener); };
  }, [html, specs]);
}

const TEXT_LIKE = new Set(['text', 'search', 'tel', 'email', 'url', 'password', 'number', undefined]);

/**
 * Wires `onChange` for a native form control rendered inside the wrapper —
 * `input` events for text-like inputs/textareas, `change` for
 * checkbox/radio/select, the same split React itself makes so `onChange`
 * behaves the way it would on a real React `<input>`. The control's initial
 * `value`/`checked` is whatever the template baked in from props — this does
 * not force the DOM back to a controlled value on every render, since doing
 * that on every keystroke is exactly what loses the caret (see the vanilla
 * textarea/codeField controllers' own comments on this).
 */
export function useChangeHandler(ref, html, props, enabled) {
  const propsRef = useRef(props);
  propsRef.current = props;

  useEffect(() => {
    const root = ref.current;
    if (!root || !enabled) return undefined;
    const fire = (e) => { const h = propsRef.current.onChange; if (typeof h === 'function') h(e); };
    const onInput = (e) => {
      const t = e.target;
      if (t.tagName === 'TEXTAREA' || (t.tagName === 'INPUT' && TEXT_LIKE.has(t.type))) fire(e);
    };
    const onChange = (e) => {
      const t = e.target;
      if (t.tagName === 'SELECT' || (t.tagName === 'INPUT' && (t.type === 'checkbox' || t.type === 'radio'))) fire(e);
    };
    root.addEventListener('input', onInput);
    root.addEventListener('change', onChange);
    return () => { root.removeEventListener('input', onInput); root.removeEventListener('change', onChange); };
  }, [html, enabled]);
}

/**
 * Builds a stateless wrapper around one `(props) => html` template.
 *
 * The wrapper renders fresh on every prop change (no reconciliation — see
 * the module doc) inside a `display: contents` div, so the real root element
 * the template produced participates directly in the parent's flex/grid
 * layout rather than sitting inside an extra box. Slot props are portaled in
 * (see `useSlotResolution`/`useSlotPortals`), so a nested wrapped component
 * passed as e.g. `children` keeps its own event handlers.
 *
 * `opts.listSlotKeys` is `{ propName: fieldKeys | null }` for an
 * array-shaped prop whose entries carry `Slot` fields (Menu's `items`,
 * Table's `rows`) — see `useListSlotResolution`. `null` field keys means
 * "check every field on every entry" (Table's rows: column keys are
 * caller-defined, so there's no fixed list).
 */
export function makeTemplateComponent(displayName, templateFn, opts = {}) {
  const { slotKeys = [], handlers = [], withChange = false, listSlotKeys = {} } = opts;
  const listProps = Object.keys(listSlotKeys);
  const Component = React.forwardRef(function TemplateComponent(props, forwardedRef) {
    const ref = useRef(null);
    const [resolvedProps, portalSpecs] = useSlotResolution(props, slotKeys);
    for (const propName of listProps) {
      if (!(propName in resolvedProps)) continue;
      // eslint-disable-next-line react-hooks/rules-of-hooks -- listProps is fixed per component (from opts), so this loop runs the same hooks in the same order every render, same guarantee as calling them unrolled.
      const [resolvedList, listPortals] = useListSlotResolution(resolvedProps[propName], listSlotKeys[propName]);
      resolvedProps[propName] = resolvedList;
      portalSpecs.push(...listPortals);
    }
    const html = templateFn(resolvedProps);
    useDomHandlers(ref, html, handlers, props);
    useChangeHandler(ref, html, props, withChange);
    const portals = useSlotPortals(ref, portalSpecs);
    useForwardRootRef(ref, forwardedRef);
    return React.createElement(
      React.Fragment,
      null,
      React.createElement('div', {
        ref,
        style: { display: 'contents' },
        'data-lds-component': displayName,
        dangerouslySetInnerHTML: { __html: html },
      }),
      ...portals,
    );
  });
  Component.displayName = displayName;
  return Component;
}

function valuesEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false;
  // Two placeholder-slot `raw()` objects (see useSlotResolution) with the same
  // markup are the same config even though the object literal wrapping them is
  // rebuilt fresh every render.
  if (typeof a.__html === 'string' && typeof b.__html === 'string') return a.__html === b.__html;
  // A shallow array — e.g. SegmentedControl's `options` — is very commonly a
  // fresh literal every render even when its contents haven't changed; a
  // one-level comparison catches that common case without pretending to be a
  // real deep-equality check.
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((v, i) => valuesEqual(v, b[i]));
  }
  return false;
}

function configEqual(a, b) {
  if (a === b) return true;
  if (!a || !b) return false;
  const ak = Object.keys(a);
  const bk = Object.keys(b);
  if (ak.length !== bk.length) return false;
  return ak.every((k) => valuesEqual(a[k], b[k]));
}

/**
 * A stable function identity that always calls the LATEST `fn` passed in —
 * the "latest ref" pattern. Needed alongside `useConfirmedValue` below: if a
 * config's `onChange` field were a fresh closure every render (the usual
 * case — an inline arrow function, or even `useConfirmedValue`'s own
 * wrapper if it weren't stabilised), `useControllerMount`'s config
 * comparison would see a "change" on every render from that alone and call
 * `update()` regardless of whether anything meaningful actually changed.
 */
export function useStableCallback(fn) {
  const ref = useRef(fn);
  ref.current = fn;
  const stableRef = useRef((...args) => ref.current(...args));
  return stableRef.current;
}

/**
 * Marks the render a controlled form control's own change handler is about
 * to trigger as self-inflicted, so `useControllerMount` skips calling
 * `update()` for it. This is a causality flag, not a value comparison — an
 * earlier version of this tried to detect "is the incoming value the same
 * one we just reported" by comparing strings, but that's the WRONG test:
 * during active typing the value legitimately differs on every keystroke
 * ('h' → 'hi' → …), so a value-equality check never matches and update()
 * still fires on every keystroke. What actually distinguishes "the vanilla
 * controller's own `onInput` already patched the live DOM for this" from
 * "a consumer changed `value` from outside" isn't the value itself, it's
 * WHO caused the re-render — so this just tracks that directly.
 *
 * For a controller whose `update()` does a full re-render (Textarea,
 * CodeField — see their own `mountX` source: `update()` always calls
 * `render()`, unlike their internal keystroke handling, which patches in
 * place specifically to avoid this), skipping matters: without it, typing
 * destroys and recreates the focused element on every character.
 *
 * Returns `markSelfChange()` — call it synchronously inside the change
 * handler, before calling the consumer's own `onChange`, so the flag is set
 * before the `setState` inside that call can schedule the render it's
 * meant to cover. `useControllerMount` consumes (resets) the flag on every
 * render regardless of whether it skipped, so a later render triggered by
 * something else is judged normally.
 */
export function useSelfChangeFlag() {
  const ref = useRef(false);
  const markSelfChange = useStableCallback(() => { ref.current = true; });
  return [ref, markSelfChange];
}

/**
 * Wraps a `mountX(el, config) -> {dispose(), update(config)}` controller as
 * a ref to a `display: contents` container. Mounts once; calls `update()`
 * only when `config` is actually different from what was last applied —
 * `config` is a fresh object literal on every render regardless (this is
 * called from a component's own render body), so reference equality alone
 * would call `update()` on every render, which for a controller like
 * Tooltip's (full re-render on every `update()`) replaces its DOM every
 * time and, combined with useSlotPortals noticing a "new" element, loops.
 */
export function useControllerMount(mountFn, config, selfChangeRef) {
  const ref = useRef(null);
  const controllerRef = useRef(null);
  const configRef = useRef(config);
  const appliedConfigRef = useRef(null);
  const isFirstUpdate = useRef(true);
  configRef.current = config;

  useLayoutEffect(() => {
    controllerRef.current = mountFn(ref.current, configRef.current);
    appliedConfigRef.current = configRef.current;
    return () => {
      if (controllerRef.current) controllerRef.current.dispose();
      controllerRef.current = null;
      isFirstUpdate.current = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mountFn]);

  useLayoutEffect(() => {
    if (isFirstUpdate.current) { isFirstUpdate.current = false; return; }
    const wasSelfChange = !!(selfChangeRef && selfChangeRef.current);
    if (selfChangeRef) selfChangeRef.current = false;
    const changed = !configEqual(appliedConfigRef.current, configRef.current);
    appliedConfigRef.current = configRef.current;
    if (wasSelfChange || !changed) return; // already reflected in the DOM — see useSelfChangeFlag's doc comment
    if (controllerRef.current) controllerRef.current.update(configRef.current);
  });

  return ref;
}
