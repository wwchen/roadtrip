import { useEffect, useRef, useState } from 'react';
import { Button, type ButtonProps } from '@lew-ds/lds-react';

/** How long an armed button stays armed before disarming itself. */
const ARM_TIMEOUT_MS = 5000;

/**
 * Extends `ButtonProps` rather than `Omit`-ing `children`/`onClick`/`armed` out of
 * it: LDS's `HtmlProps` carries an `[attr: string]: unknown` index signature, and
 * `Omit` over such a type collapses to that signature alone, silently degrading
 * every named prop — including `onClick`'s parameter — to `any`. This component
 * overrides those three after the spread instead.
 */
export interface ConfirmButtonProps extends ButtonProps {
  /** Resting label, e.g. "Delete". */
  label: string;
  /** Armed label. Defaults to `${label}?`. */
  confirmLabel?: string;
  /**
   * Accessible name while armed.
   *
   * Defaults to `confirmLabel` when one is given, and to
   * `Confirm ${label.toLowerCase()}` otherwise. That order matters: an explicit
   * `confirmLabel` is the author's chosen wording, so deriving the announced name
   * from `label` instead would make the button read "Confirm disconnect" while
   * announcing "Confirm disconnect slack". Without a `confirmLabel` the visible
   * text is terse ("Delete?") and the derived name is the explicit one, which is
   * the split we want.
   */
  confirmAriaLabel?: string;
  /** Fired on the SECOND click only. */
  onConfirm: () => void;
}

/**
 * A button whose action needs two clicks: the first arms it, the second fires.
 *
 * Replaces `web/design-system/double-confirm-button.js` and the copy that Phase 1
 * inlined into `WatchTable`. Extracted at the third site — deleting a watch,
 * signing out, and disconnecting Slack — rather than written a third time.
 *
 * **It disarms itself** after `ARM_TIMEOUT_MS`, which the inlined copy did not. That
 * matters because the armed state is a promise that the next click destroys
 * something: an armed button left on screen is a trap, and the user's next click on
 * what looks like an ordinary button deletes.
 *
 * Blur-to-disarm was tried and dropped, so the timeout is the whole safety net. LDS
 * wires only the handlers a component declares in its own spec (see `runtime.jsx`),
 * so an `onBlur` passed through props is never attached — and `blur` does not bubble
 * to the wrapper it would be attached to anyway.
 *
 * The accessible name changes with the state, so a screen reader announces the
 * armed step rather than silently re-labelling the same control.
 */
export function ConfirmButton({
  label,
  confirmLabel,
  confirmAriaLabel,
  onConfirm,
  ...props
}: ConfirmButtonProps) {
  const [armed, setArmed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    if (!armed) return;
    timer.current = setTimeout(() => setArmed(false), ARM_TIMEOUT_MS);
    return () => clearTimeout(timer.current);
  }, [armed]);

  return (
    <Button
      {...props}
      armed={armed}
      aria-label={
        armed ? (confirmAriaLabel ?? confirmLabel ?? `Confirm ${label.toLowerCase()}`) : label
      }
      onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        setArmed(false);
        onConfirm();
      }}
    >
      {armed ? (confirmLabel ?? `${label}?`) : label}
    </Button>
  );
}
