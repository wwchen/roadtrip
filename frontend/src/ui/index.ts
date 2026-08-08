// ---------------------------------------------------------------------------
// LDS adapter layer (`@ui`).
//
// LDS (`matthewlew/lds`) is the React component library that replaces the
// legacy `web/design-system/*` primitives. It is vendored into
// `frontend/vendor/` for now (npm cannot install a single workspace member of a
// git monorepo) and consumed through the `vendor/*` npm workspaces.
//
// Every migrated page imports its primitives from `@ui` rather than reaching
// for `@lew/lds-react` directly. That keeps the swap to a published
// `@lew/lds-react` registry dependency a change to this file's specifier and
// `package.json` only — no call-site churn.
//
// Styles are NOT re-exported here; they are a side effect, loaded once per page
// entry via `@ui/styles.css` (see that file for the theme contract).
// ---------------------------------------------------------------------------

export * from '@lew/lds-react';

// ---------------------------------------------------------------------------
// Type corrections.
//
// Narrow gaps where `@lew/lds-react`'s declarations are stricter than the
// package's documented runtime behavior. An explicit export here shadows the
// star export above, so call sites need no casts and there is exactly one place
// to delete when upstream tightens this up.
// ---------------------------------------------------------------------------
import type { ForwardRefExoticComponent, ReactNode, RefAttributes } from 'react';
import { Table as LdsTable, type TableProps as LdsTableProps } from '@lew/lds-react';

/**
 * `Table`, with column labels and cell values widened to React nodes.
 *
 * `@lew/lds-react`'s own comments say "a column's `label` accepts a React node"
 * and "every cell value accepts a React node (e.g. a `<Tag>` for a status
 * column)", but both types are still `Slot` from `@lew/lds/templates`
 * (`string | number | RawHtml | null | undefined | false`), inherited from the
 * framework-free package. The runtime flattens nodes through `toSlot`; only the
 * declaration lags.
 */
export interface TableColumn {
  key: string;
  label?: ReactNode;
}

export interface TableProps extends Omit<LdsTableProps, 'columns' | 'rows'> {
  columns?: TableColumn[];
  rows?: Record<string, ReactNode>[];
}

export const Table = LdsTable as unknown as ForwardRefExoticComponent<
  TableProps & RefAttributes<HTMLTableElement>
>;
