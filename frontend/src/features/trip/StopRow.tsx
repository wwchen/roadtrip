// One trip-planner row. HTML drag-and-drop keeps reordering dependency-free; the
// resulting transition is handled by pure domain logic.
import { useEffect, useRef, useState } from 'react';
import type { TripMode } from '@/stores/tripStore';
import { isStructuralRow, stopPlaceholder, stopRole, type StopSlot } from '@/domain/trip/stops';

export interface StopRowProps {
  index: number;
  /** How many rows the list has — decides this row's role. */
  count: number;
  stop: StopSlot;
  mode: TripMode;
  /** The text in the box, which is not the stop: a half-typed query is neither. */
  value: string;
  draggable: boolean;
  /** Take focus on this render, then call `onFocusHandled`. */
  autoFocus: boolean;
  onFocusHandled: () => void;
  onChange: (value: string) => void;
  onFocus: () => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  onRemove: () => void;
  onUseCurrentLocation: () => void;
  onReorder: (from: number, to: number) => void;
}

export function StopRow({
  index,
  count,
  stop,
  mode,
  value,
  draggable,
  autoFocus,
  onFocusHandled,
  onChange,
  onFocus,
  onKeyDown,
  onRemove,
  onUseCurrentLocation,
  onReorder,
}: StopRowProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [dropTarget, setDropTarget] = useState(false);

  // A one-shot focus, driven by the controller rather than by the DOM: the row that
  // wants focus is decided by the edit that just happened (a cleared endpoint, a new
  // via), and `autoFocus` as a real React prop only fires on mount.
  useEffect(() => {
    if (!autoFocus) return;
    inputRef.current?.focus();
    onFocusHandled();
  }, [autoFocus, onFocusHandled]);

  const placeholder = stopPlaceholder(index, count, mode);
  const role = mode === 'directions' ? stopRole(index, count) : 'origin';
  const filled = stop != null;
  // Read off the stop rather than taken as a prop: the placeholder IS the loading
  // state, and a second flag beside it could disagree with the row it describes
  // after a drag.
  const locating = stop?.pending === true;
  /**
   * Whether this row can go, as opposed to just be emptied.
   *
   * The vanilla's `canRemove`. It is not the same as `draggable`, which is what an
   * earlier version of this component used: in a two-row directions trip every row
   * is draggable but neither can be removed, so an empty endpoint was showing an X
   * that `removeStopAt` deliberately no-ops.
   */
  const removable = mode !== 'directions' || count >= 3;

  return (
    <div
      className={`tb-row${dragging ? ' dragging' : ''}${dropTarget ? ' drop-target' : ''}`}
      // `data-i` is a test seam, not state: `SmokeTest.kt` addresses rows as
      // `.tb-row[data-i="0"] .tb-input`, and keeping the attribute means the smoke
      // suite does not need a different selector per tree while both exist.
      data-i={index}
      draggable={draggable}
      onDragStart={(event) => {
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', String(index));
        setDragging(true);
      }}
      onDragEnd={() => setDragging(false)}
      onDragOver={(event) => {
        // Without preventDefault the drop never fires — the HTML5 default is
        // "reject", and this is the line every DnD implementation forgets.
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        setDropTarget(true);
      }}
      onDragLeave={() => setDropTarget(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDropTarget(false);
        // `parseInt`, not `Number`: an external drag (a file, an image, a selection)
        // carries no `text/plain` payload, and `Number('')` is 0 — which would
        // silently move row 0 onto whichever row was dropped on. NaN is ignored by
        // `reorderStops`.
        const from = Number.parseInt(event.dataTransfer.getData('text/plain'), 10);
        onReorder(from, index);
      }}
    >
      <span className={`tb-icon ${role === 'origin' ? '' : role === 'destination' ? 'last' : 'via'}`} />
      <input
        ref={inputRef}
        className="tb-input"
        data-i={index}
        type="text"
        autoComplete="off"
        placeholder={placeholder}
        aria-label={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onFocus={onFocus}
        onKeyDown={onKeyDown}
      />
      <button
        type="button"
        className="tb-locate"
        title="Use current location"
        aria-label="Use current location"
        disabled={locating}
        onClick={onUseCurrentLocation}
      >
        <LocateIcon />
      </button>
      {/* An empty structural row has nothing to clear and cannot be removed, so it
          gets no button rather than a button that does nothing.

          The label names the row, where the vanilla labelled every X simply "Clear":
          a screen reader on a three-stop trip announced four identical "Clear"
          buttons, counting the one that clears the whole trip. */}
      {filled || removable ? (
        <button
          type="button"
          className="tb-x"
          // "Clear" or "Remove" by what the button will actually do: a filled
          // endpoint empties in place, everything else takes the row with it.
          aria-label={`${
            filled && isStructuralRow(index, count, mode) ? 'Clear' : 'Remove'
          } ${rowName(index, count, mode)}`}
          onClick={onRemove}
        >
          <CloseIcon />
        </button>
      ) : null}
    </div>
  );
}

/**
 * What this row is, for the X button's accessible name.
 *
 * Lower-case because it is a fragment ("Clear destination"), and distinct from the
 * placeholder because "Search a place or pin…" does not read as the object of a verb.
 */
function rowName(index: number, count: number, mode: TripMode): string {
  if (mode !== 'directions') return 'search';
  switch (stopRole(index, count)) {
    case 'origin':
      return 'origin';
    case 'destination':
      return 'destination';
    default:
      return `stop ${index}`;
  }
}

/** Inline SVGs, as in the vanilla: two icons do not justify a sprite fetch. */
function LocateIcon() {
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2v3" />
      <path d="M12 19v3" />
      <path d="M2 12h3" />
      <path d="M19 12h3" />
      <path d="M18.4 5.6l-2.1 2.1" />
      <path d="M7.7 16.3l-2.1 2.1" />
      <path d="M5.6 5.6l2.1 2.1" />
      <path d="M16.3 16.3l2.1 2.1" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  );
}
