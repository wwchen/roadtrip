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

// ---------------------------------------------------------------------------
// Local additions.
//
// Components that exist to make an LDS constraint safe to use, rather than to
// restyle anything. They belong here because the constraint they encode is LDS's,
// so they disappear if upstream ever ships controlled inputs.
//
// `LinkButton` is the one exception and is here for the same reason: LDS has no
// primitive for an action word inside a sentence — `Button` is a control and
// `Link` is an `<a>` — so the shape is drawn here once instead of per feature.
// ---------------------------------------------------------------------------

export { SeededTextField, type SeededTextFieldProps } from './SeededTextField';
export { ConfirmButton, type ConfirmButtonProps } from './ConfirmButton';
export { SecretField, type SecretFieldProps } from './SecretField';
export { LinkButton, type LinkButtonProps } from './LinkButton';

// ---------------------------------------------------------------------------
// Type corrections.
//
// Narrow gaps where `@lew-ds/lds-react`'s declarations are stricter than the
// package's documented runtime behavior. An explicit export here shadows the
// star export above, so call sites need no casts and there is exactly one place
// to delete when upstream tightens this up.
// ---------------------------------------------------------------------------
import type { ForwardRefExoticComponent, ReactNode, RefAttributes } from 'react';
import {
  Modal as LdsModal,
  Table as LdsTable,
  type ModalProps as LdsModalProps,
  type Size,
  type TableProps as LdsTableProps,
} from '@lew-ds/lds-react';

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

/**
 * `Table`, with column labels and cell values widened to React nodes.
 *
 * `@lew-ds/lds-react`'s own comments say "a column's `label` accepts a React node"
 * and "every cell value accepts a React node (e.g. a `<Tag>` for a status
 * column)", but both types are still `Slot` from `@lew-ds/lds/templates`
 * (`string | number | RawHtml | null | undefined | false`), inherited from the
 * framework-free package. The runtime flattens nodes through `toSlot`; only the
 * declaration lags.
 */
export interface TableColumn {
  key: string;
  label?: ReactNode;
}

/**
 * The `Omit` here is safe only because `columns` and `rows` are the *only* named
 * props LDS's `TableProps` adds, and both are redeclared below. LDS's `HtmlProps`
 * has an `[attr: string]: unknown` index signature, and `Omit` over such a type
 * collapses it to that signature alone — so if upstream ever adds a callback (an
 * `onSort`, say), this would silently degrade it to `any` rather than fail to
 * compile. Widen by intersection, not `Omit`, if that day comes. See
 * `SeededTextField` for the same trap caught the hard way.
 */
export interface TableProps extends Omit<LdsTableProps, 'columns' | 'rows'> {
  columns?: TableColumn[];
  rows?: Record<string, ReactNode>[];
}

export const Table = LdsTable as unknown as ForwardRefExoticComponent<
  TableProps & RefAttributes<HTMLTableElement>
>;
