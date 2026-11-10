// ---------------------------------------------------------------------------
// LDS adapter layer (`@ui`).
//
// LDS (`matthewlew/lds`) is the React component library that replaces the
// previous app-specific primitives. It is consumed from npm under the
// `@lew-ds` scope.
//
// Every migrated page imports its primitives from `@ui` rather than reaching
// for `@lew-ds/lds-react` directly. That keeps the package boundary in one
// place and avoids call-site churn if the dependency changes again.
//
// Styles are NOT re-exported here; they are a side effect, loaded once per page
// entry via `@ui/styles.css` (see that file for the theme contract).
// ---------------------------------------------------------------------------

export * from '@lew-ds/lds-react';
export { IconSprite } from '@lew-ds/lds-react/icon-sprite';

// ---------------------------------------------------------------------------
// Local additions.
//
// Components that exist to make an LDS constraint safe to use, rather than to
// restyle anything. They belong here because the constraint they encode is LDS's,
// so they disappear if upstream ever ships controlled inputs.
//
// `LinkButton` and `MonthCalendar` are the exceptions, and are here for the same
// reason: LDS has no primitive for an action word inside a sentence — `Button` is
// a control and `Link` is an `<a>` — nor any calendar, so each shape is drawn here
// once instead of per feature.
// ---------------------------------------------------------------------------

export { SeededTextField, type SeededTextFieldProps } from './SeededTextField';
export { ConfirmButton, type ConfirmButtonProps } from './ConfirmButton';
export { SecretField, type SecretFieldProps } from './SecretField';
export { LinkButton, type LinkButtonProps } from './LinkButton';
export { MonthCalendar, type MonthCalendarProps } from './MonthCalendar';

// ---------------------------------------------------------------------------
// Type corrections.
//
// Narrow gaps where `@lew-ds/lds-react`'s declarations are stricter than the
// package's documented runtime behavior. An explicit export here shadows the
// star export above, so call sites need no casts and there is exactly one place
// to delete when upstream tightens this up.
// ---------------------------------------------------------------------------
import type { ForwardRefExoticComponent, RefAttributes } from 'react';
import { Modal as LdsModal, type ModalProps as LdsModalProps, type Size } from '@lew-ds/lds-react';

/**
 * `Modal`, with `size` widened to include `xl`/`2xl`.
 *
 * `@lew-ds/lds`'s CSS ships `.lds-modal--xl` and `.lds-modal--2xl`
 * (`--modal-xl`/`--modal-2xl`, in `css/lds.css`), but `ModalProps.size` is `Size`
 * (`'sm' | 'md' | 'lg'`) — the same shared type `Button` and `TextField` use,
 * which genuinely has no `xl`/`2xl`. Modal's own range is wider than the shared
 * type, so only Modal is corrected here.
 */
export type ModalSize = Size | 'xl' | '2xl';

export interface ModalProps extends Omit<LdsModalProps, 'size'> {
  size?: ModalSize;
}

export const Modal = LdsModal as unknown as ForwardRefExoticComponent<
  ModalProps & RefAttributes<HTMLDivElement>
>;
