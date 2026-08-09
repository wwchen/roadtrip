// The search dropdown under the rows.
//
// Port of `renderDropdown` / `refreshDropdownActive` / the arrow-key half of
// `onInputKey` in web/topbar.js. The active row is a prop rather than a class the
// component toggles on DOM nodes, because the keyboard handler lives in the topbar
// (the input has focus, not the list) and one owner of "which row is active" is what
// keeps the highlight and the Enter key in agreement.
import { useEffect, useRef } from 'react';
import { token, KIND_TOKEN } from '@tokens';
import { sectionHeaders, type SearchKind, type SearchResult } from './search-results';

export interface SearchDropdownProps {
  results: readonly SearchResult[];
  /** The keyboard-selected row, or -1 when the mouse is in charge. */
  activeIndex: number;
  onPick: (result: SearchResult) => void;
}

/**
 * The chip colour for a result kind.
 *
 * Through `token()` rather than a CSS class per kind, because the palette is
 * already a table (`KIND_TOKEN`) and duplicating it as seven CSS rules is how the
 * two drift apart. A kind the palette does not name falls back to the neutral one.
 */
const kindColor = (kind: SearchKind): string => token(KIND_TOKEN[kind] ?? '--rt-kind-default');

export function SearchDropdown({ results, activeIndex, onPick }: SearchDropdownProps) {
  const listRef = useRef<HTMLDivElement>(null);
  const headers = sectionHeaders(results);

  // Keep the keyboard selection in view. The list is 320px tall and a query can
  // return twelve rows, so arrowing past the fold otherwise moves an invisible
  // highlight.
  useEffect(() => {
    if (activeIndex < 0) return;
    const row = listRef.current?.querySelector(`[data-result-index="${activeIndex}"]`);
    // Optional call because jsdom implements no scrolling at all: the highlight is
    // testable, keeping it in view is not.
    (row as HTMLElement | null)?.scrollIntoView?.({ block: 'nearest' });
  }, [activeIndex]);

  if (results.length === 0) return null;

  return (
    <div className="tb-dropdown open" ref={listRef} role="listbox" aria-label="Search results">
      {results.map((result, index) => (
        <div key={`${result.source}-${result.poiId ?? result.name}-${index}`}>
          {headers[index] ? <div className="tb-section">{headers[index]}</div> : null}
          <div
            className={`tb-result${index === activeIndex ? ' active' : ''}`}
            data-result-index={index}
            role="option"
            aria-selected={index === activeIndex}
            // mousedown, not click: the input blurs on mousedown, and a click
            // handler would fire after the blur had already closed the list.
            onMouseDown={(event) => {
              event.preventDefault();
              onPick(result);
            }}
          >
            <span className="tb-kind" style={{ background: kindColor(result.kind) }}>
              {result.kind}
            </span>
            <span className="tb-name">
              {result.name}
              {result.sub ? <span className="tb-sub"> {result.sub}</span> : null}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}
