// The five stateful components. Each vanilla one ships a controller
// (`mountX(el, config) -> {dispose(), update()}`) because its first paint
// can't tolerate a full re-render — a re-rendered <textarea> loses the
// caret, a re-rendered code field loses focus mid-digit. This wraps that
// controller in `useControllerMount` (see runtime.jsx) instead of giving
// each one a template wrapper: mount once, `update()` on every render after.
//
// Their `onChange` signatures are whatever the controller already calls:
// Textarea's forwards the native input event (so it behaves like a real
// React `<textarea onChange>`); SegmentedControl's and CodeField's call back
// with the resolved value directly, not an event — that's the vanilla API,
// carried through rather than reshaped to look uniform.
//
// Slot props (CodeField/Textarea's label/help/error, Tooltip's label/
// children) go through the same portal mechanism the template wrapper uses
// (see runtime.jsx's useSlotResolution/useSlotPortals doc comments) rather
// than `toSlot`, so a nested wrapped component keeps its own handlers and
// rendering it doesn't reenter React's render dispatcher.
import React, { useContext, useMemo } from 'react';
import {
  mountCodeField, mountSegmentedControl, mountTextarea, mountToasts, mountTooltip,
} from '@lew/lds';
import {
  toSlot, useControllerMount, useForwardRootRef, useSelfChangeFlag, useSlotPortals,
  useSlotResolution, useStableCallback,
} from './runtime.jsx';

const CONTAINER_STYLE = { display: 'contents' };

export const CodeField = React.forwardRef(function CodeField(props, forwardedRef) {
  const { value, defaultValue, onChange, ...rest } = props;
  const [selfChangeRef, markSelfChange] = useSelfChangeFlag();
  const handleChange = useStableCallback((code) => { markSelfChange(); if (onChange) onChange(code); });
  const rawConfig = { ...rest, value: value ?? defaultValue, onChange: handleChange };
  const [resolved, portalSpecs] = useSlotResolution(rawConfig, ['label', 'help', 'error']);
  const ref = useControllerMount(mountCodeField, resolved, selfChangeRef);
  const portals = useSlotPortals(ref, portalSpecs);
  useForwardRootRef(ref, forwardedRef);
  return React.createElement(
    React.Fragment, null,
    React.createElement('div', { ref, style: CONTAINER_STYLE }),
    ...portals,
  );
});

export const SegmentedControl = React.forwardRef(function SegmentedControl(props, forwardedRef) {
  const { value, defaultValue, onChange, ...rest } = props;
  const [selfChangeRef, markSelfChange] = useSelfChangeFlag();
  const handleChange = useStableCallback((next) => { markSelfChange(); if (onChange) onChange(next); });
  const config = { ...rest, value: value ?? defaultValue, onChange: handleChange };
  const ref = useControllerMount(mountSegmentedControl, config, selfChangeRef);
  useForwardRootRef(ref, forwardedRef);
  return React.createElement('div', { ref, style: CONTAINER_STYLE });
});

export const Textarea = React.forwardRef(function Textarea(props, forwardedRef) {
  const { value, defaultValue, onChange, ...rest } = props;
  const [selfChangeRef, markSelfChange] = useSelfChangeFlag();
  const handleChange = useStableCallback((e) => { markSelfChange(); if (onChange) onChange(e); });
  const rawConfig = { ...rest, value: value ?? defaultValue, onChange: handleChange };
  const [resolved, portalSpecs] = useSlotResolution(rawConfig, ['label', 'help', 'error']);
  const ref = useControllerMount(mountTextarea, resolved, selfChangeRef);
  const portals = useSlotPortals(ref, portalSpecs);
  useForwardRootRef(ref, forwardedRef);
  return React.createElement(
    React.Fragment, null,
    React.createElement('div', { ref, style: CONTAINER_STYLE }),
    ...portals,
  );
});

export const Tooltip = React.forwardRef(function Tooltip(props, forwardedRef) {
  const { children, label, ...rest } = props;
  const rawConfig = { ...rest, children, label };
  const [resolved, portalSpecs] = useSlotResolution(rawConfig, ['children', 'label']);
  const ref = useControllerMount(mountTooltip, resolved);
  const portals = useSlotPortals(ref, portalSpecs);
  useForwardRootRef(ref, forwardedRef);
  return React.createElement(
    React.Fragment, null,
    React.createElement('div', { ref, style: CONTAINER_STYLE }),
    ...portals,
  );
});

// Toast is global chrome, not a per-instance component — mount one
// <ToastProvider> near the root and raise messages through useToast()
// rather than rendering a <Toast> per message:
//
//   <ToastProvider>
//     <App/>
//   </ToastProvider>
//
//   const { toast } = useToast();
//   toast({ status: 'success', children: 'Saved.' });
//   toast({ status: 'error', title: 'Failed', children: 'Retry.' }); // stays
//
// A raised toast has no persistent React element to portal into — `toast()`
// runs from a click handler, not a render, so flattening its slot props
// (title/children/actions/icon) with `toSlot` is safe here (see toSlot's own
// doc comment on why it ISN'T safe inline during a render).
//
// `placement`/`duration`/`max`/`iconHref` are read once at mount, same as
// the vanilla `mountToasts` they wrap — it has no `update()`, so there is
// nothing to keep in sync on a later prop change.
const ToastContext = React.createContext(null);

export function ToastProvider({ children, placement, duration, max, iconHref }) {
  const containerRef = React.useRef(null);
  const apiRef = React.useRef(null);

  React.useLayoutEffect(() => {
    apiRef.current = mountToasts(containerRef.current, { placement, duration, max, iconHref });
    return () => { apiRef.current.dispose(); apiRef.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const api = useMemo(() => ({
    toast: (opts) => apiRef.current && apiRef.current.toast(resolveToastOptions(opts)),
    dismiss: (id) => apiRef.current && apiRef.current.dismiss(id),
  }), []);

  return React.createElement(
    ToastContext.Provider,
    { value: api },
    children,
    React.createElement('div', { ref: containerRef, style: CONTAINER_STYLE }),
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast() must be called inside a <ToastProvider>.');
  return ctx;
}

function resolveToastOptions(opts) {
  const o = typeof opts === 'string' ? { children: opts } : { ...opts };
  if ('children' in o) o.children = toSlot(o.children);
  if ('title' in o) o.title = toSlot(o.title);
  if ('actions' in o) o.actions = toSlot(o.actions);
  if ('icon' in o) o.icon = toSlot(o.icon);
  return o;
}
