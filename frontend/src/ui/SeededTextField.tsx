import { useState } from 'react';
import { TextField, type TextFieldProps } from '@lew-ds/lds-react';

/**
 * Note this extends `TextFieldProps` rather than `Omit`-ing `value`/`defaultValue`
 * out of it. LDS's `HtmlProps` carries an `[attr: string]: unknown` index
 * signature (any extra prop becomes an HTML attribute), and `Omit` over a type
 * with an index signature collapses it to that signature alone — which silently
 * erases `onChange`'s parameter type and makes every handler `any`. So `seed` wins
 * over `value`/`defaultValue` at the call site below instead of in the type.
 */
export interface SeededTextFieldProps extends TextFieldProps {
  /**
   * The value to show, read once when this component mounts and ignored
   * thereafter. Pass the live mirrored value; remount to reseed.
   *
   * Supersedes `value` and `defaultValue`, which this component always overrides.
   */
  seed: string;
}

/**
 * A `TextField` that seeds itself, at its own mount, from the current value.
 *
 * LDS form controls are uncontrolled: `value` is honored on first render only,
 * and a changed prop re-renders the template and swaps the input's DOM, which
 * mid-typing eats the caret and every keystroke after the first. The usual answer
 * is `defaultValue` from a snapshot the parent froze at ITS mount — which is
 * correct for a field that lives as long as its parent, and wrong for one the
 * parent unmounts and remounts, e.g. a field gated on a toggle. There the
 * parent's snapshot is stale: the remounted input shows the old value while the
 * mirrored React state (and so the submitted payload) holds the newer one. The
 * user sees one address and a different one is saved.
 *
 * Snapshotting here fixes that without reintroducing the caret bug. Each mount
 * takes a fresh reading of `seed`; later changes to `seed` are ignored for as long
 * as this instance lives, which is exactly the uncontrolled contract. A parent
 * that wants to force a reseed remounts this component — by unmounting it, or with
 * a new `key`.
 */
export function SeededTextField({ seed, ...props }: SeededTextFieldProps) {
  const [initial] = useState(seed);
  // Both written after the spread so `seed` is the only thing that can seed this
  // field: a caller's `value` would otherwise reach LDS and act as a second,
  // competing initial value.
  return <TextField {...props} value={undefined} defaultValue={initial} />;
}
