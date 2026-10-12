import {
  forwardRef,
  useEffect,
  useRef,
  type ForwardedRef,
  type ForwardRefExoticComponent,
  type RefAttributes,
} from 'react';
import { TextField as LdsTextField, type TextFieldProps } from '@lew-ds/lds-react';

/**
 * Input types `@lew-ds/lds-react` never calls `onChange` for.
 *
 * Its `useChangeHandler` (`runtime.jsx`) relays native `input` events only for a
 * fixed `TEXT_LIKE` set — text, search, tel, email, url, password, number — and
 * `change` events only for select, checkbox and radio. The date and time family
 * is in neither, so a `TextField type="date"` rendered a working picker whose
 * every pick was silently dropped by the form mirroring it.
 */
const UNWIRED_TYPES = new Set(['date', 'datetime-local', 'month', 'time', 'week']);

/**
 * LDS `TextField`, with `onChange` wired for the date and time input types.
 *
 * For those types it listens on a `display: contents` host of its own, which the
 * input's events bubble through however often LDS replaces the template's DOM.
 * Both `input` and `change` are heard, and a value already reported is not
 * reported again — the same de-duplication React's own `onChange` does, so a
 * browser that fires both for one pick calls back once. Every other type renders
 * the LDS component untouched. If LDS ever wires these types itself, delete this
 * and the export that shadows the original.
 *
 * Typed by cast, like `Modal` and `Table` in `index.ts`: `forwardRef`'s
 * `PropsWithoutRef` is an `Omit`, and over LDS's index-signature props it would
 * collapse every named prop to `unknown`.
 */
const CorrectedTextField = forwardRef(function TextField(props: TextFieldProps, ref: ForwardedRef<HTMLDivElement>) {
  const hostRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(props.onChange);
  onChangeRef.current = props.onChange;
  const unwired = props.type !== undefined && UNWIRED_TYPES.has(props.type);

  useEffect(() => {
    const host = hostRef.current;
    if (!host || !unwired) return undefined;
    const reported = new WeakMap<HTMLInputElement, string>();
    const relay = (event: Event): void => {
      const input = event.target;
      if (!(input instanceof HTMLInputElement) || reported.get(input) === input.value) return;
      reported.set(input, input.value);
      onChangeRef.current?.(event);
    };
    host.addEventListener('input', relay);
    host.addEventListener('change', relay);
    return () => {
      host.removeEventListener('input', relay);
      host.removeEventListener('change', relay);
    };
  }, [unwired]);

  if (!unwired) return <LdsTextField {...props} ref={ref} />;
  return (
    <div ref={hostRef} style={{ display: 'contents' }}>
      <LdsTextField {...props} ref={ref} />
    </div>
  );
});

export const TextField = CorrectedTextField as unknown as ForwardRefExoticComponent<
  TextFieldProps & RefAttributes<HTMLDivElement>
>;
